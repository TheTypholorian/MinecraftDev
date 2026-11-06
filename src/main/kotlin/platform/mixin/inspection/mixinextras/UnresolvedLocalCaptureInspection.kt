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
import com.demonwav.mcdev.platform.mixin.util.LocalInfo
import com.demonwav.mcdev.platform.mixin.util.MixinConstants
import com.demonwav.mcdev.platform.mixin.util.mixinTargets
import com.demonwav.mcdev.platform.mixin.util.unwrapLocalRef
import com.demonwav.mcdev.util.cached
import com.demonwav.mcdev.util.constantValue
import com.demonwav.mcdev.util.findAnnotations
import com.demonwav.mcdev.util.findContainingClass
import com.demonwav.mcdev.util.findContainingMethod
import com.demonwav.mcdev.util.findModule
import com.demonwav.mcdev.util.mapFirstNotNull
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.parentOfType

class UnresolvedLocalCaptureInspection : MixinInspection() {
    override fun getStaticDescription() = "Verifies targets of @Local annotations"

    override fun buildVisitor(holder: ProblemsHolder): PsiElementVisitor = object : JavaElementVisitor() {
        override fun visitAnnotation(localAnnotation: PsiAnnotation) {
            if (localAnnotation.qualifiedName != MixinConstants.MixinExtras.LOCAL) {
                return
            }

            val parameter = localAnnotation.parentOfType<PsiParameter>() ?: return
            val method = parameter.findContainingMethod() ?: return
            val jumpIndex = localAnnotation.findDeclaredAttributeValue("jump")?.constantValue as? Int
            val jumps = method.parameterList.parameters.mapNotNull { it.getAnnotation(MixinConstants.MixinExtras.JUMP) }

            val targets = if (jumpIndex != null && jumpIndex in jumps.indices) {
                val jumpAnnotation = jumps[jumpIndex]
                method.annotations.mapFirstNotNull { annotation ->
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
            } else {
                method.annotations.mapFirstNotNull { annotation ->
                    val handler = MixinAnnotationHandler.forMixinAnnotation(annotation, holder.project) as? InsnInjectorAnnotationHandler
                            ?: return@mapFirstNotNull null
                    handler.resolveInstructions(annotation)
                } ?: return
            }

            val module = method.findModule() ?: return

            val localInfo = LocalInfo.fromAnnotation(parameter.type.unwrapLocalRef(), localAnnotation)

            for (target in targets) {
                val matchingLocals = localInfo.matchLocals(
                    module, target.method.clazz, target.method.method, target.result.insn,
                    CollectVisitor.Mode.RESOLUTION
                ) ?: continue
                if (matchingLocals.size != 1) {
                    holder.registerProblem(
                        localAnnotation.nameReferenceElement ?: localAnnotation,
                        "@Local does not match any or matched multiple local variables in the target method"
                    )
                    return
                }
            }
        }
    }
}
