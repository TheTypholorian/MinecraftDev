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
import com.demonwav.mcdev.platform.mixin.util.LocalInfo
import com.demonwav.mcdev.platform.mixin.util.MixinConstants
import com.demonwav.mcdev.platform.mixin.util.mixinTargets
import com.demonwav.mcdev.platform.mixin.util.toPsiType
import com.demonwav.mcdev.platform.mixin.util.unwrapLocalRef
import com.demonwav.mcdev.platform.mixin.util.unwrapStackRef
import com.demonwav.mcdev.util.addAnnotation
import com.demonwav.mcdev.util.cached
import com.demonwav.mcdev.util.constantValue
import com.demonwav.mcdev.util.findAnnotations
import com.demonwav.mcdev.util.findContainingClass
import com.demonwav.mcdev.util.findContainingMethod
import com.demonwav.mcdev.util.findModule
import com.demonwav.mcdev.util.mapFirstNotNull
import com.intellij.codeInsight.intention.FileModifier
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.util.PsiModificationTracker

class MissingJumpLocalsInspection : MixinInspection() {
    override fun getStaticDescription() = "Ensures that needed jump locals are set"

    override fun buildVisitor(holder: ProblemsHolder): PsiElementVisitor = object : JavaElementVisitor() {
        override fun visitAnnotation(jumpAnnotation: PsiAnnotation) {
            if (jumpAnnotation.qualifiedName != MixinConstants.MixinExtras.JUMP) {
                return
            }

            val parameter = jumpAnnotation.parent?.parent as? PsiParameter ?: return
            val method = jumpAnnotation.findContainingMethod() ?: return
            val module = method.findModule() ?: return
            val jumps = method.parameterList.parameters.mapNotNull { it.getAnnotation(MixinConstants.MixinExtras.JUMP) }
            val jumpIndex = jumps.indexOf(jumpAnnotation)
            val jumpLocals = method.parameterList.parameters.mapNotNull { parameter -> parameter.getAnnotation(MixinConstants.MixinExtras.LOCAL)?.let { parameter to it } }
                .filter { (parameter, annotation) -> annotation.findDeclaredAttributeValue("jump")?.constantValue as? Int == jumpIndex }

            val injectionTargets = (method.annotations.mapFirstNotNull { annotation ->
                val handler = MixinAnnotationHandler.forMixinAnnotation(annotation, holder.project) as? InsnInjectorAnnotationHandler
                    ?: return@mapFirstNotNull null
                handler.resolveInstructions(annotation)
            } ?: return).map { it to AsmDfaUtil.getLocalVariableTypes(holder.project, it.method.clazz, it.method.method, it.result.insn.next) }
            val jumpTargets = (method.annotations.mapFirstNotNull { annotation ->
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
            } ?: return).map { it to AsmDfaUtil.getLocalVariableTypes(holder.project, it.method.clazz, it.method.method, it.result.insn.next) }

            val localsToAdd = mutableMapOf<Int, PsiType>()

            for ((injectionTarget, sourceLocals) in injectionTargets) {
                sourceLocals ?: continue
                val jumpLocals = jumpLocals.associateBy { (parameter, annotation) ->
                    LocalInfo.fromAnnotation(parameter.type.unwrapStackRef() ?: parameter.type.unwrapLocalRef(), annotation).matchLocals(
                        module, injectionTarget.method.clazz, injectionTarget.method.method, injectionTarget.result.insn,
                        CollectVisitor.Mode.RESOLUTION
                    )?.singleOrNull()?.index
                }

                for ((jumpTarget, targetLocals) in jumpTargets) {
                    targetLocals ?: continue

                    targetLocals.forEachIndexed { index, targetLocal ->
                        val sourceLocal = sourceLocals[index]

                        if (targetLocal != null && sourceLocal != targetLocal) {
                            val jumpLocal = jumpLocals[index]
                            val targetPsiLocal = targetLocal.toPsiType(JavaPsiFacade.getElementFactory(holder.project))

                            if (jumpLocal?.first?.type?.unwrapStackRef() != targetPsiLocal) {
                                if (localsToAdd.put(index, targetPsiLocal) != targetPsiLocal) {
                                    holder.registerProblem(
                                        jumpAnnotation.nameReferenceElement ?: jumpAnnotation,
                                        "Incompatible locals at jump targets, split into separate injectors."
                                    )
                                    return
                                }
                            }
                        }
                    }
                }
            }

            if (localsToAdd.isNotEmpty()) {
                holder.registerProblem(
                    jumpAnnotation.nameReferenceElement ?: jumpAnnotation,
                    "Missing jump locals",
                    InjectMissingLocalsQuickFix(localsToAdd, parameter)
                )
            }
        }
    }

    private class InjectMissingLocalsQuickFix(
        @FileModifier.SafeFieldForPreview
        private val localsToAdd: Map<Int, PsiType>,
        parameter: PsiParameter
    ) : LocalQuickFixOnPsiElement(parameter) {
        override fun getFamilyName() = "Add needed jump locals"

        override fun getText() = "Add needed jump locals"

        override fun invoke(project: Project, psiFile: PsiFile, startElement: PsiElement, endElement: PsiElement) {
            val parameter = startElement as? PsiParameter ?: return
            val method = parameter.findContainingMethod() ?: return

            val factory = JavaPsiFacade.getElementFactory(project)
            val parameters = localsToAdd.map { (key, type) ->
                factory.createParameter("local$key", type).also {
                    it.addAnnotation("${MixinConstants.MixinExtras.LOCAL}(jump = $key)")
                }
            }
            parameters.fold(parameter as PsiElement) { accum, parameter ->
                method.parameterList.addAfter(parameter, accum)
            }
        }
    }
}
