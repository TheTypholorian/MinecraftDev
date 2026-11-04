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
import com.demonwav.mcdev.platform.mixin.util.isJumpInfo
import com.demonwav.mcdev.util.findAnnotations
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTypesUtil

class JumpNonJumpInfoComplexInspection : MixinInspection() {
    override fun getStaticDescription() = "Reports when a complex @Jump parameter isn't of type JumpInfoComplex"

    override fun buildVisitor(holder: ProblemsHolder) = object : JavaElementVisitor() {
        override fun visitAnnotation(annotation: PsiAnnotation) {
            if (!annotation.hasQualifiedName(MixinConstants.MixinExtras.JUMP)) {
                return
            }

            val parameter = annotation.parent?.parent as? PsiParameter ?: return
            val typeElement = parameter.typeElement ?: return

            if (annotation.findDeclaredAttributeValue("localsToModify")?.findAnnotations()?.isNotEmpty() == true && PsiTypesUtil.getPsiClass(parameter.type)?.qualifiedName == MixinConstants.MixinExtras.JUMP_INFO) {
                holder.registerProblem(
                    typeElement,
                    "Complex @Jump parameter must be of type JumpInfoComplex",
                    ReplaceWithJumpInfoFix(typeElement),
                )
            }
        }
    }

    private class ReplaceWithJumpInfoFix(typeElement: PsiTypeElement) : LocalQuickFixOnPsiElement(typeElement) {
        override fun getText() = "Replace with JumpInfoComplex"

        override fun getFamilyName() = "Replace with JumpInfoComplex"

        override fun invoke(
            project: Project,
            file: PsiFile,
            startElement: PsiElement,
            endElement: PsiElement
        ) {
            val typeElement = startElement as? PsiTypeElement ?: return
            val newTypeElement = JavaPsiFacade.getElementFactory(project).createTypeElement(JavaPsiFacade.getElementFactory(project).createTypeByFQClassName(MixinConstants.MixinExtras.JUMP_INFO_COMPLEX))
            val elementToReformat = typeElement.replace(newTypeElement)
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(elementToReformat)
        }
    }
}
