/*
 * Copyright 2012 Sergey Ignatov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.erlang;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.parameterInfo.*;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.erlang.bif.ErlangBifTable;
import org.intellij.erlang.psi.*;
import org.intellij.erlang.psi.impl.ErlangPsiImplUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ignatov
 */
public class ErlangParameterInfoHandler implements ParameterInfoHandler<ErlangArgumentList, Object> {
  @Override
  public boolean couldShowInLookup() {
    return true;
  }

  @Override
  public Object[] getParametersForLookup(LookupElement item, ParameterInfoContext context) {
    return ArrayUtil.EMPTY_OBJECT_ARRAY;
  }

  @Override
  public Object[] getParametersForDocumentation(Object p, ParameterInfoContext context) {
    return null;
  }

  @Override
  public ErlangArgumentList findElementForParameterInfo(CreateParameterInfoContext context) {
    return getErlangArgumentList(context);
  }

  @Override
  public ErlangArgumentList findElementForUpdatingParameterInfo(UpdateParameterInfoContext context) {
    return getErlangArgumentList(context);
  }

  @Nullable
  private static ErlangArgumentList getErlangArgumentList(ParameterInfoContext context) {
    PsiElement at = context.getFile().findElementAt(context.getOffset());
    return PsiTreeUtil.getParentOfType(at, ErlangArgumentList.class);
  }

  @Override
  public void showParameterInfo(@NotNull ErlangArgumentList args, CreateParameterInfoContext context) {
    ErlangFunctionCallExpression erlFunctionCall = PsiTreeUtil.getParentOfType(args, ErlangFunctionCallExpression.class);
    if (erlFunctionCall != null) {
      PsiReference reference = erlFunctionCall.getReference();
      PsiElement resolve = reference != null ? reference.resolve() : null;
      List<ErlangFunctionClause> clauses = new ArrayList<ErlangFunctionClause>();
      if (resolve instanceof ErlangFunction) {
        List<ErlangFunctionClause> clauseList = ((ErlangFunction) resolve).getFunctionClauseList();
        clauses.addAll(clauseList);
      }
      else if (reference instanceof PsiPolyVariantReference) {
        ResolveResult[] resolveResults = ((PsiPolyVariantReference) reference).multiResolve(true);
        for (ResolveResult result : resolveResults) {
          PsiElement element = result.getElement();
          if (element instanceof ErlangFunction) {
            clauses.addAll(((ErlangFunction) element).getFunctionClauseList());
          }
        }
      }
      if (clauses.size() > 0) {
        context.setItemsToShow(ArrayUtil.toObjectArray(clauses));
        context.showHint(args, args.getTextRange().getStartOffset(), this);
      }
      else {
        final ErlangGlobalFunctionCallExpression erlGlobalFunctionCall = PsiTreeUtil.getParentOfType(
          erlFunctionCall, ErlangGlobalFunctionCallExpression.class);
        if (erlGlobalFunctionCall != null) {
          final ErlangModuleRef moduleRef = erlGlobalFunctionCall.getModuleRef();
          if (moduleRef != null) {
            final String moduleName = moduleRef.getText();
            final String functionName = erlFunctionCall.getNameIdentifier().getText();
            final int arity = erlFunctionCall.getArgumentList().getExpressionList().size();
            final String bifParams = ErlangBifTable.getBifParams(moduleName, functionName, arity);
            if (bifParams != null) {
              context.setItemsToShow(new Object[]{bifParams});
              context.showHint(args, args.getTextRange().getStartOffset(), this);
            }
          }
        }
      }
    }
  }

  @Override
  public void updateParameterInfo(@NotNull ErlangArgumentList place, UpdateParameterInfoContext context) {
    context.setCurrentParameter(ParameterInfoUtils.getCurrentParameterIndex(place.getNode(), context.getOffset(), ErlangTypes.ERL_COMMA));
  }

  @Override
  public String getParameterCloseChars() {
    return ",){}";
  }

  @Override
  public boolean tracksParameterIndex() {
    return true;
  }

  @Override
  public void updateUI(@Nullable Object p, @NotNull ParameterInfoUIContext context) {
    if (p == null) {
      context.setUIComponentEnabled(false);
      return;
    }

    int index = context.getCurrentParameterIndex();

    final StringBuilder builder = new StringBuilder();

    int start = 0;
    int end = 0;
    if (p instanceof ErlangFunctionClause) {
      final Ref<ErlangFunTypeArguments> argsRef = Ref.create();

      PsiElement parent = ((ErlangFunctionClause) p).getParent();
      ErlangSpecification specification = parent instanceof ErlangFunction ? ErlangPsiImplUtil.getSpecification((ErlangFunction) parent) : null;

      if (specification != null) {
        specification.accept(new ErlangRecursiveVisitor() {
          @Override
          public void visitFunTypeArguments(@NotNull ErlangFunTypeArguments o) {
            argsRef.setIfNull(o);
          }
        });
      }

      List<ErlangArgumentDefinition> args = ((ErlangFunctionClause) p).getArgumentDefinitionList().getArgumentDefinitionList();

      @Nullable ErlangFunTypeArguments arguments = argsRef.get();
      List<ErlangTopType> topTypeList = arguments == null ? ContainerUtil.<ErlangTopType>emptyList() : arguments.getTopTypeList();
      boolean typesAvailable = topTypeList.size() == args.size();

      for (int i = 0; i < args.size(); i++) {
        if (i != 0) builder.append(", ");
        if (index == i) start = builder.length();
        builder.append(args.get(i).getExpression().getText().replaceAll(" ", "").trim());
        if (typesAvailable) {

          ErlangTopType topType = topTypeList.get(i);
          ErlangType type = topType.getTopType100T().getType();
          final ErlangQVar var = type.getQVar();
          if (var != null) {
            if (specification != null) {
              final Ref<ErlangType> itemTypeRef = Ref.create();
              specification.accept(new ErlangRecursiveVisitor() {
                @Override
                public void visitTypeGuard(@NotNull ErlangTypeGuard o) {
                  ErlangTopType item = ContainerUtil.getFirstItem(o.getTopTypeList());
                  ErlangQVar qVar = item == null ? null : item.getQVar();
                  PsiReference reference = qVar == null ? null : qVar.getReference();
                  PsiElement resolve = reference == null ? null : reference.resolve();
                  if (var.equals(resolve)) {
                    itemTypeRef.setIfNull(item.getTopType100T().getType());
                  }
                }
              });
              if (!itemTypeRef.isNull()) {
                builder.append(" :: ");
                builder.append(itemTypeRef.get().getText());
              }
            }
          }
          else {
            builder.append(" :: ");
            builder.append(type.getText());
          }
        }
        if (index == i) end = builder.length();
      }
    }
    else if (p instanceof String) {
      final String bifParams = (String) p;
      builder.append(bifParams);
      for (int i = 0; i < index; ++i) {
        start = bifParams.indexOf(',', start + 1);
      }
      end = bifParams.indexOf(',', start + 1);
      end = (end == -1 ? bifParams.length() : end);
    }

    if (builder.length() == 0) {
      builder.append("<no parameters>");
    }

    context.setupUIComponentPresentation(
      builder.toString(),
      start,
      end,
      !context.isUIComponentEnabled(),
      false,
      false,
      context.getDefaultParameterColor());
  }
}
