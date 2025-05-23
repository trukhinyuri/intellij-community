// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.reflectiveAccess;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.callMatcher.CallMapper;
import com.siyeh.ig.psiutils.ExpressionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.intellij.psi.CommonClassNames.JAVA_UTIL_LIST;
import static com.intellij.psi.impl.source.resolve.reference.impl.JavaReflectionReferenceUtil.*;

final class JavaLangReflectHandleInvocationChecker {
  private static final Logger LOG = Logger.getInstance(JavaLangReflectHandleInvocationChecker.class);

  private static final String INVOKE = "invoke";
  private static final String INVOKE_EXACT = "invokeExact";
  private static final String INVOKE_WITH_ARGUMENTS = "invokeWithArguments";
  private static final String JAVA_LANG_INVOKE_METHOD_HANDLE = "java.lang.invoke.MethodHandle";

  private static final CallMapper<List<Supplier<ReflectiveType>>> LAZY_SIGNATURE_MAPPER = new CallMapper<List<Supplier<ReflectiveType>>>()
    .register(METHOD_TYPE_WITH_CLASSES_MATCHER, call -> getLazyMethodSignatureForTypes(call))
    .register(METHOD_TYPE_WITH_LIST_MATCHER, call -> getLazyMethodSignatureForReturnTypeAndList(call))
    .register(METHOD_TYPE_WITH_ARRAY_MATCHER, call -> getLazyMethodSignatureForReturnTypeAndArray(call))
    .register(METHOD_TYPE_WITH_METHOD_TYPE_MATCHER, call -> getLazyMethodSignatureForReturnTypeAndMethodType(call))
    .register(GENERIC_METHOD_TYPE_MATCHER, call -> getLazyMethodSignatureForGenericMethodType(call));

  private static final Set<String> METHOD_HANDLE_INVOKE_NAMES = Set.of(INVOKE, INVOKE_EXACT, INVOKE_WITH_ARGUMENTS);

  static boolean checkMethodHandleInvocation(@NotNull PsiMethodCallExpression methodCall, @NotNull ProblemsHolder holder) {
    final String referenceName = methodCall.getMethodExpression().getReferenceName();
    if (referenceName != null && METHOD_HANDLE_INVOKE_NAMES.contains(referenceName)) {
      final PsiMethod method = methodCall.resolveMethod();
      if (method != null && isClassWithName(method.getContainingClass(), JAVA_LANG_INVOKE_METHOD_HANDLE)) {
        if (isWithDynamicArguments(methodCall)) {
          return true;
        }
        final PsiExpression qualifierDefinition = findDefinition(methodCall.getMethodExpression().getQualifierExpression());
        if (qualifierDefinition instanceof PsiMethodCallExpression) {
          checkMethodHandleInvocation((PsiMethodCallExpression)qualifierDefinition, methodCall, holder);
        }
      }
      return true;
    }
    return false;
  }

  private static void checkMethodHandleInvocation(@NotNull PsiMethodCallExpression handleFactoryCall,
                                                  @NotNull PsiMethodCallExpression invokeCall,
                                                  @NotNull ProblemsHolder holder) {
    final String factoryMethodName = handleFactoryCall.getMethodExpression().getReferenceName();
    if (factoryMethodName != null && JavaLangInvokeHandleSignatureInspection.KNOWN_METHOD_NAMES.contains(factoryMethodName)) {

      final PsiExpression[] handleFactoryArguments = handleFactoryCall.getArgumentList().getExpressions();
      final boolean isFindConstructor = FIND_CONSTRUCTOR.equals(factoryMethodName);
      if (handleFactoryArguments.length == 3 && !isFindConstructor ||
          handleFactoryArguments.length == 2 && isFindConstructor ||
          handleFactoryArguments.length == 4 && FIND_SPECIAL.equals(factoryMethodName)) {

        final PsiMethod factoryMethod = handleFactoryCall.resolveMethod();
        if (factoryMethod != null && isClassWithName(factoryMethod.getContainingClass(), JAVA_LANG_INVOKE_METHOD_HANDLES_LOOKUP)) {
          final ReflectiveType receiverType = getReflectiveType(handleFactoryArguments[0]);
          final boolean isExact = INVOKE_EXACT.equals(invokeCall.getMethodExpression().getReferenceName());

          if (isFindConstructor) {
            if (!checkMethodSignature(invokeCall, handleFactoryArguments[1], isExact, true, 0, holder)) return;
            checkReturnType(invokeCall, receiverType, isExact, holder);
            return;
          }

          final PsiExpression typeExpression = handleFactoryArguments[2];
          switch (factoryMethodName) {
            case FIND_VIRTUAL, FIND_SPECIAL -> {
              if (!checkMethodSignature(invokeCall, typeExpression, isExact, false, 1, holder)) return;
              checkCallReceiver(invokeCall, receiverType, holder);
            }
            case FIND_STATIC -> checkMethodSignature(invokeCall, typeExpression, isExact, false, 0, holder);
            case FIND_GETTER -> {
              if (!checkGetter(invokeCall, typeExpression, isExact, 1, holder)) return;
              checkCallReceiver(invokeCall, receiverType, holder);
            }
            case FIND_SETTER -> {
              if (!checkSetter(invokeCall, typeExpression, isExact, 1, holder)) return;
              checkCallReceiver(invokeCall, receiverType, holder);
            }
            case FIND_STATIC_GETTER -> checkGetter(invokeCall, typeExpression, isExact, 0, holder);
            case FIND_STATIC_SETTER -> checkSetter(invokeCall, typeExpression, isExact, 0, holder);
            case FIND_VAR_HANDLE, FIND_STATIC_VAR_HANDLE -> { }
          }
        }
      }
    }
  }

  static void checkCallReceiver(@NotNull PsiMethodCallExpression invokeCall,
                                @Nullable ReflectiveType expectedType,
                                ProblemsHolder holder) {
    final PsiExpressionList argumentList = invokeCall.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    if (arguments.length == 0) return;

    final PsiExpression receiverArgument = arguments[0];
    LOG.assertTrue(receiverArgument != null);
    final PsiExpression receiverDefinition = findDefinition(receiverArgument);
    if (ExpressionUtils.isNullLiteral(receiverDefinition)) {
      holder.registerProblem(receiverArgument, JavaBundle.message("inspection.reflect.handle.invocation.receiver.null"));
      return;
    }

    if (expectedType != null) {
      if (!isCompatible(expectedType.getType(), receiverArgument.getType())) {
        holder.registerProblem(receiverArgument,
                               JavaBundle.message("inspection.reflect.handle.invocation.receiver.incompatible",
                                                         expectedType.getQualifiedName()));
      }
      else if (receiverArgument != receiverDefinition && receiverDefinition != null) {
        if (!isCompatible(expectedType.getType(), receiverDefinition.getType())) {
          holder.registerProblem(receiverArgument, JavaBundle.message("inspection.reflect.handle.invocation.receiver.incompatible",
                                                                             expectedType.getQualifiedName()));
        }
      }
    }
  }

  private static boolean checkMethodSignature(@NotNull PsiMethodCallExpression invokeCall,
                                              @NotNull PsiExpression signatureExpression,
                                              boolean isExact,
                                              boolean isConstructor,
                                              int argumentOffset,
                                              @NotNull ProblemsHolder holder) {
    final List<Supplier<ReflectiveType>> lazyMethodSignature = getLazyMethodSignature(signatureExpression);
    if (lazyMethodSignature == null) return true;

    if (!isConstructor && !lazyMethodSignature.isEmpty()) {
      final ReflectiveType returnType = lazyMethodSignature.get(0).get();
      checkReturnType(invokeCall, returnType, isExact, holder);
    }

    final PsiExpressionList argumentList = invokeCall.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    final JavaReflectionInvocationInspection.Arguments actualArguments =
      JavaReflectionInvocationInspection.getActualMethodArguments(arguments, argumentOffset, true);
    if (actualArguments == null) return true;

    final int requiredArgumentCount = lazyMethodSignature.size() - 1; // -1 excludes the return type
    final boolean maybeVararg;
    if (!isExact && requiredArgumentCount > 0) {
      ReflectiveType type = lazyMethodSignature.get(requiredArgumentCount).get();
      maybeVararg = type != null && type.getType() instanceof PsiArrayType;
    } else {
      maybeVararg = false;
    }
    PsiExpression[] expressions = actualArguments.expressions();
    if (!maybeVararg || expressions.length < requiredArgumentCount - 1) {
      if (!checkArgumentCount(expressions, requiredArgumentCount, argumentOffset, argumentList, holder)) return false;
    }

    for (int i = 0; i < expressions.length; i++) {
      int parameterIndex = maybeVararg && i >= requiredArgumentCount - 1 ? requiredArgumentCount : i + 1;
      final ReflectiveType requiredType = lazyMethodSignature.get(parameterIndex).get();
      checkArgumentType(expressions[i], requiredType, argumentList, isExact, maybeVararg && i >= requiredArgumentCount - 1, holder);
    }
    return true;
  }


  static void checkArgumentType(@NotNull PsiExpression argument,
                                @Nullable ReflectiveType requiredType,
                                @NotNull PsiExpressionList argumentList,
                                boolean isExact,
                                boolean maybeVararg,
                                @NotNull ProblemsHolder holder) {
    if (requiredType == null) return;
    final PsiType actualType = argument.getType();
    if (actualType == null) return;
    if (!isCompatible(requiredType, actualType, isExact)) {
      if (maybeVararg) {
        ReflectiveType componentType = requiredType.getArrayComponentType();
        if (componentType != null) {
          requiredType = componentType;
          if (isCompatible(requiredType, actualType, isExact)) return;
        }
      }
      if (PsiTreeUtil.isAncestor(argumentList, argument, false)) {
        holder.registerProblem(argument,
                               JavaBundle.message(isExact
                                                         ? "inspection.reflect.handle.invocation.argument.not.exact"
                                                         : "inspection.reflection.invocation.argument.not.assignable",
                                                         requiredType.getQualifiedName()));
      }
    }
    else if (requiredType.isPrimitive()) {
      final PsiExpression definition = findDefinition(argument);
      if (definition != null && PsiTypes.nullType().equals(definition.getType())) {
        if (PsiTreeUtil.isAncestor(argumentList, argument, false)) {
          holder.registerProblem(argument,
                                 JavaBundle.message("inspection.reflect.handle.invocation.primitive.argument.null",
                                                           requiredType.getQualifiedName()));
        }
      }
    }
  }

  static void checkReturnType(@NotNull PsiMethodCallExpression invokeCall,
                              @Nullable ReflectiveType requiredType,
                              boolean isExact,
                              @NotNull ProblemsHolder holder) {
    if (requiredType == null) return;
    final PsiElement invokeParent = invokeCall.getParent();
    PsiType actualType = null;
    PsiElement problemElement = null;
    if (invokeParent instanceof PsiTypeCastExpression) {
      final PsiTypeElement castTypeElement = ((PsiTypeCastExpression)invokeParent).getCastType();
      if (castTypeElement != null) {
        actualType = castTypeElement.getType();
        problemElement = castTypeElement;
      }
    }
    else if (invokeParent instanceof PsiAssignmentExpression) {
      actualType = ((PsiAssignmentExpression)invokeParent).getLExpression().getType();
    }
    else if (invokeParent instanceof PsiVariable) {
      actualType = ((PsiVariable)invokeParent).getType();
    }

    if (actualType != null && !isCompatible(requiredType, actualType, isExact)) {
      if (problemElement == null) {
        problemElement = invokeCall.getMethodExpression();
      }
      holder.registerProblem(problemElement, JavaBundle.message(isExact || requiredType.isPrimitive()
                                                                       ? "inspection.reflect.handle.invocation.result.not.exact"
                                                                       : "inspection.reflect.handle.invocation.result.not.assignable",
                                                                       requiredType.getQualifiedName()));
    }
  }

  private static @Nullable List<Supplier<ReflectiveType>> getLazyMethodSignature(@Nullable PsiExpression methodTypeExpression) {
    final PsiExpression typeDefinition = findDefinition(methodTypeExpression);
    if (typeDefinition instanceof PsiMethodCallExpression) {
      return LAZY_SIGNATURE_MAPPER.mapFirst(((PsiMethodCallExpression)typeDefinition));
    }
    return null;
  }

  private static @Nullable @Unmodifiable List<Supplier<ReflectiveType>> getLazyMethodSignatureForGenericMethodType(
    @NotNull PsiMethodCallExpression methodTypeExpression
  ) {
    final PsiExpression[] arguments = methodTypeExpression.getArgumentList().getExpressions();
    final Pair.NonNull<Integer, Boolean> signature = getGenericSignature(arguments);
    if (signature != null) {
      final int objectArgCount = signature.getFirst();
      final boolean finalArray = signature.getSecond();
      if (objectArgCount == 0 && !finalArray) {
        return Collections.emptyList();
      }
      final PsiClassType javaLangObject =
        PsiType.getJavaLangObject(methodTypeExpression.getManager(), methodTypeExpression.getResolveScope());
      final ReflectiveType objectType = ReflectiveType.create(javaLangObject, false);
      final List<ReflectiveType> argumentTypes = new ArrayList<>();
      argumentTypes.add(objectType); // return type
      for (int i = 0; i < objectArgCount; i++) {
        argumentTypes.add(objectType);
      }
      if (finalArray) {
        argumentTypes.add(ReflectiveType.arrayOf(objectType));
      }
      return ContainerUtil.map(argumentTypes, type -> (() -> type));
    }
    return null;
  }

  private static @Nullable List<Supplier<ReflectiveType>> getLazyMethodSignatureForReturnTypeAndMethodType(
    @NotNull PsiMethodCallExpression callExpression
  ) {
    final PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
    if (arguments.length == 2) {
      final PsiExpression methodType = findInnermostMethodType(arguments[1]);
      if (methodType != null) {
        final List<Supplier<ReflectiveType>> nestedSignature = getLazyMethodSignature(methodType);
        if (nestedSignature != null) {
          final List<Supplier<ReflectiveType>> signature = new ArrayList<>(nestedSignature);
          if (!signature.isEmpty()) {
            final PsiExpression returnType = arguments[0];
            signature.set(0, () -> getReflectiveType(returnType));
          }
          return signature;
        }
      }
    }
    return null;
  }

  private static @Nullable @Unmodifiable List<Supplier<ReflectiveType>> getLazyMethodSignatureForReturnTypeAndArray(@NotNull PsiMethodCallExpression call) {
    final PsiExpression[] arguments = call.getArgumentList().getExpressions();
    if (arguments.length == 2) {
      final PsiExpression returnType = findDefinition(arguments[0]);
      final PsiExpression secondArgument = arguments[1];
      final List<PsiExpression> components = getVarargs(secondArgument);
      if (components != null) {
        final List<PsiExpression> signature = ContainerUtil.prepend(components, returnType);
        return ContainerUtil.map(signature, parameter -> (() -> getReflectiveType(parameter)));
      }
    }
    return null;
  }

  private static @Nullable @Unmodifiable List<Supplier<ReflectiveType>> getLazyMethodSignatureForReturnTypeAndList(@NotNull PsiMethodCallExpression call) {
    final PsiExpression[] arguments = call.getArgumentList().getExpressions();
    if (arguments.length == 2) {
      final PsiExpression list = arguments[1];
      final List<PsiExpression> components = getListComponents(list);
      if (components != null) {
        final PsiExpression returnType = findDefinition(arguments[0]);
        final List<PsiExpression> signature = ContainerUtil.prepend(components, returnType);
        return ContainerUtil.map(signature, argument -> (() -> getReflectiveType(argument)));
      }
    }
    return null;
  }

  private static @Nullable @Unmodifiable List<Supplier<ReflectiveType>> getLazyMethodSignatureForTypes(@NotNull PsiMethodCallExpression call) {
    final PsiExpression[] expressions = call.getArgumentList().getExpressions();
    if (expressions.length != 0) {
      return ContainerUtil.map(expressions, argument -> (() -> getReflectiveType(argument)));
    }
    return null;
  }

  private static boolean checkGetter(@NotNull PsiMethodCallExpression invokeCall,
                                     @NotNull PsiExpression typeExpression,
                                     boolean isExact,
                                     int argumentOffset, ProblemsHolder holder) {
    final PsiExpressionList argumentList = invokeCall.getArgumentList();
    if (!checkArgumentCount(argumentList.getExpressions(), argumentOffset, 0, argumentList, holder)) return false;

    final ReflectiveType resultType = getReflectiveType(typeExpression);
    if (resultType != null) {
      checkReturnType(invokeCall, resultType, isExact, holder);
    }
    return true;
  }

  private static boolean checkSetter(@NotNull PsiMethodCallExpression invokeCall,
                                     @NotNull PsiExpression typeExpression,
                                     boolean isExact,
                                     int argumentOffset, ProblemsHolder holder) {
    final PsiExpressionList argumentList = invokeCall.getArgumentList();
    final PsiExpression[] arguments = argumentList.getExpressions();
    if (!checkArgumentCount(arguments, argumentOffset + 1, 0, argumentList, holder)) return false;

    LOG.assertTrue(arguments.length == argumentOffset + 1);
    final ReflectiveType requiredType = getReflectiveType(typeExpression);
    checkArgumentType(arguments[argumentOffset], requiredType, argumentList, isExact, false, holder);

    final PsiElement invokeParent = invokeCall.getParent();
    if (!(invokeParent instanceof PsiStatement)) {
      holder.registerProblem(invokeCall.getMethodExpression(),
                             JavaBundle.message(isExact
                                                       ? "inspection.reflect.handle.invocation.result.void"
                                                       : "inspection.reflect.handle.invocation.result.null"));
    }
    return true;
  }

  static boolean checkArgumentCount(PsiExpression @NotNull [] arguments,
                                    int requiredArgumentCount,
                                    int argumentOffset,
                                    @NotNull PsiElement problemElement,
                                    @NotNull ProblemsHolder holder) {
    if (requiredArgumentCount < 0) return false;
    if (arguments.length != requiredArgumentCount) {
      holder.registerProblem(problemElement, JavaBundle.message(
        "inspection.reflection.invocation.argument.count", requiredArgumentCount + argumentOffset));
      return false;
    }
    return true;
  }

  private static boolean isCompatible(@NotNull ReflectiveType requiredType, @NotNull PsiType actualType, boolean isExact) {
    if (isExact) {
      return requiredType.isEqualTo(actualType);
    }
    return requiredType.isAssignableFrom(actualType) || actualType.isAssignableFrom(requiredType.getType());
  }


  private static boolean isCompatible(@NotNull PsiType expectedType, @Nullable PsiType actualType) {
    return actualType != null && (expectedType.isAssignableFrom(actualType) || actualType.isAssignableFrom(expectedType));
  }

  private static boolean isWithDynamicArguments(@NotNull PsiMethodCallExpression invokeCall) {
    if (INVOKE_WITH_ARGUMENTS.equals(invokeCall.getMethodExpression().getReferenceName())) {
      final PsiExpression[] arguments = invokeCall.getArgumentList().getExpressions();
      if (arguments.length == 1) {
        return isVarargAsArray(arguments[0]) ||
               InheritanceUtil.isInheritor(arguments[0].getType(), JAVA_UTIL_LIST);
      }
    }
    return false;
  }
}
