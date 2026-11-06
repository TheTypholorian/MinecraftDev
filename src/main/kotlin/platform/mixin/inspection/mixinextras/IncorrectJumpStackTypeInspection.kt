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

import com.demonwav.mcdev.platform.mixin.handlers.InjectorAnnotationHandler
import com.demonwav.mcdev.platform.mixin.handlers.InsnInjectorAnnotationHandler
import com.demonwav.mcdev.platform.mixin.handlers.MixinAnnotationHandler
import com.demonwav.mcdev.platform.mixin.handlers.injectionPoint.AtResolver
import com.demonwav.mcdev.platform.mixin.handlers.injectionPoint.CollectVisitor
import com.demonwav.mcdev.platform.mixin.inspection.MixinInspection
import com.demonwav.mcdev.platform.mixin.util.AsmDfaUtil
import com.demonwav.mcdev.platform.mixin.util.MixinConstants
import com.demonwav.mcdev.platform.mixin.util.mixinTargets
import com.demonwav.mcdev.platform.mixin.util.toPsiType
import com.demonwav.mcdev.platform.mixin.util.wrapStackRef
import com.demonwav.mcdev.util.cached
import com.demonwav.mcdev.util.constantValue
import com.demonwav.mcdev.util.findAnnotations
import com.demonwav.mcdev.util.findContainingClass
import com.demonwav.mcdev.util.findContainingMethod
import com.demonwav.mcdev.util.mapFirstNotNull
import com.intellij.codeInsight.intention.FileModifier
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.parentOfType
import kotlin.collections.addAll

class IncorrectJumpStackTypeInspection : MixinInspection() {
    override fun getStaticDescription() = "Verifies @JumpStack argument types"

    override fun buildVisitor(holder: ProblemsHolder): PsiElementVisitor = object : JavaElementVisitor() {
        override fun visitAnnotation(stackAnnotation: PsiAnnotation) {
            if (stackAnnotation.qualifiedName != MixinConstants.MixinExtras.JUMP_STACK) {
                return
            }

            val parameter = stackAnnotation.parentOfType<PsiParameter>() ?: return
            val method = parameter.findContainingMethod() ?: return
            val jumpIndex = stackAnnotation.findAttributeValue("jump")?.constantValue as? Int ?: return
            val stackIndex = stackAnnotation.findDeclaredAttributeValue("index")?.constantValue as? Int ?: return
            val jumps = method.parameterList.parameters.mapNotNull { it.getAnnotation(MixinConstants.MixinExtras.JUMP) }
            val jumpAnnotation = jumps.getOrNull(jumpIndex) ?: return

            val targets = method.annotations.mapFirstNotNull { annotation ->
                annotation.cached(PsiModificationTracker.MODIFICATION_COUNT) {
                    val handler = MixinAnnotationHandler.forMixinAnnotation(annotation, holder.project) as? InjectorAnnotationHandler ?: return@cached emptyList()
                    val containingClass = annotation.findContainingClass() ?: return@cached emptyList()
                    containingClass.mixinTargets.flatMap { mixinTarget ->
                        handler.resolveTarget(annotation, mixinTarget).flatMap { methodTarget ->
                            jumpAnnotation.findDeclaredAttributeValue("value")
                                ?.findAnnotations()
                                ?.flatMap { at ->
                                    AtResolver(
                                        at,
                                        methodTarget.classAndMethod.clazz,
                                        methodTarget.classAndMethod.method
                                    ).resolveInstructions(CollectVisitor.Mode.RESOLUTION).map { result ->
                                        InsnInjectorAnnotationHandler.InsnResult(methodTarget.classAndMethod, result)
                                    }
                                } ?: listOf()
                        }
                    }
                }
            } ?: return
            val expectedType = parameter.type

            for (target in targets) {
                val targetStack = AsmDfaUtil.getStackTypes(holder.project, target.method.clazz, target.method.method, target.result.insn) ?: continue

                if (stackIndex !in targetStack.indices) {
                    holder.registerProblem(
                        stackAnnotation.nameReferenceElement ?: stackAnnotation,
                        "Out of bounds stack index"
                    )
                    return
                }

                val actualType = targetStack[stackIndex]?.toPsiType(JavaPsiFacade.getElementFactory(holder.project))?.wrapStackRef(holder.project)

                if (actualType != null && expectedType != actualType) {
                    holder.registerProblem(
                        stackAnnotation.nameReferenceElement ?: stackAnnotation,
                        "Incorrect @JumpStack parameter type",
                        FixJumpStackTypeQuickFix(actualType, parameter)
                    )
                    return
                }
            }
        }
    }

    private class FixJumpStackTypeQuickFix(
        @FileModifier.SafeFieldForPreview
        private val actualType: PsiType,
        param: PsiParameter
    ) : LocalQuickFixOnPsiElement(param) {
        override fun getFamilyName(): String {
            return "Fix incorrect @JumpStack parameter type"
        }

        override fun getText(): String {
            return "Fix incorrect @JumpStack parameter type"
        }

        override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val param = startElement as? PsiParameter ?: return
            val method = param.parentOfType<PsiMethod>() ?: return
            val paramIndex = method.parameterList.getParameterIndex(param)
            val methods = mutableListOf(method)

            if (file.isPhysical) {
                methods.addAll(OverridingMethodsSearch.search(method))
            }

            for (impl in methods) {
                impl.parameterList.getParameter(paramIndex)?.typeElement?.replace(PsiElementFactory.getInstance(impl.project).createTypeElement(actualType))
            }
        }
    }
}
