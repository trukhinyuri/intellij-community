// Copyright 2000-2017 JetBrains s.r.o.
// Use of this source code is governed by the Apache 2.0 license that can be
// found in the LICENSE file.
package com.jetbrains.python.psi.impl;

import com.intellij.lang.ASTNode;
import com.jetbrains.python.psi.PyCallExpression;
import com.jetbrains.python.psi.PyElementVisitor;
import com.jetbrains.python.psi.PyUtil;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.types.PyCallableType;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;


public class PyCallExpressionImpl extends PyElementImpl implements PyCallExpression {

  public PyCallExpressionImpl(ASTNode astNode) {
    super(astNode);
  }

  @Override
  protected void acceptPyVisitor(PyElementVisitor pyVisitor) {
    pyVisitor.visitPyCallExpression(this);
  }

  @Override
  public @NotNull List<PyCallableType> multiResolveCallee(@NotNull PyResolveContext resolveContext) {
    return PyCallExpressionHelper.multiResolveCallee(this, resolveContext);
  }

  @Override
  public @NotNull List<PyArgumentsMapping> multiMapArguments(@NotNull PyResolveContext resolveContext) {
    return PyCallExpressionHelper.mapArguments(this, resolveContext);
  }

  @Override
  public String toString() {
    return "PyCallExpression: " + PyUtil.getReadableRepr(getCallee(), true); //or: getCalledFunctionReference().getReferencedName();
  }

  @Override
  public @Nullable PyType getType(@NotNull TypeEvalContext context, @NotNull TypeEvalContext.Key key) {
    return PyCallExpressionHelper.getCallType(this, context, key);
  }
}
