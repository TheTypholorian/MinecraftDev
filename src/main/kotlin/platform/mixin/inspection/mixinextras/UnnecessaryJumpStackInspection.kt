/*
 * Minecraft Development for IntelliJ
 *
 * https://mcdev.io/
 *
 * Copyright (C) 2025 minecraft-dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, version 3.0 only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.demonwav.mcdev.platform.mixin.inspection.mixinextras

import com.demonwav.mcdev.platform.mixin.inspection.MixinInspection
import com.demonwav.mcdev.platform.mixin.util.MixinConstants
import com.demonwav.mcdev.util.constantValue
import com.demonwav.mcdev.util.findContainingMethod
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.parentOfType

class UnnecessaryJumpStackInspection : MixinInspection() {
    override fun getStaticDescription() = "Verifies if @JumpStack are needed"

    override fun buildVisitor(holder: ProblemsHolder): PsiElementVisitor = object : JavaElementVisitor() {
        override fun visitAnnotation(stackAnnotation: PsiAnnotation) {
            if (stackAnnotation.qualifiedName != MixinConstants.MixinExtras.JUMP_STACK) {
                return
            }

            val parameter = stackAnnotation.parentOfType<PsiParameter>() ?: return
            val method = parameter.findContainingMethod() ?: return
            val jumpIndex = stackAnnotation.findAttributeValue("jump")?.constantValue as? Int ?: return
            val jumps = method.parameterList.parameters.mapNotNull { it.getAnnotation(MixinConstants.MixinExtras.JUMP) }
            val jumpAnnotation = jumps.getOrNull(jumpIndex) ?: return

            if (jumpAnnotation.findAttributeValue("shiftBeforeStack")?.constantValue as? Boolean == true) {
                holder.registerProblem(
                    stackAnnotation.nameReferenceElement ?: stackAnnotation,
                    "@JumpStack is not needed (and will error) as shiftBeforeStack is on",
                    RemoveUnnecessaryJumpStackQuickFix(parameter)
                )
                return
            }
        }
    }

    private class RemoveUnnecessaryJumpStackQuickFix(param: PsiParameter) : LocalQuickFixOnPsiElement(param) {
        override fun getFamilyName(): String {
            return "Remove unnecessary @JumpStack sugar for jump with shiftBeforeStack on"
        }

        override fun getText(): String {
            return "Remove unnecessary @JumpStack sugar for jump with shiftBeforeStack on"
        }

        override fun invoke(
            project: Project,
            psiFile: PsiFile,
            startElement: PsiElement,
            endElement: PsiElement
        ) {
            startElement.delete()
        }
    }
}
