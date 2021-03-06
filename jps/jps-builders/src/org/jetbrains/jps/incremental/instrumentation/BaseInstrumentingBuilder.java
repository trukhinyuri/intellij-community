/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package org.jetbrains.jps.incremental.instrumentation;

import com.intellij.compiler.instrumentation.InstrumentationClassFinder;
import com.intellij.compiler.instrumentation.InstrumenterClassWriter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.asm4.ClassReader;
import org.jetbrains.asm4.ClassWriter;
import org.jetbrains.jps.ModuleChunk;
import org.jetbrains.jps.incremental.BinaryContent;
import org.jetbrains.jps.incremental.BuilderCategory;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.CompiledClass;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;

/**
 * @author Eugene Zhuravlev
 *         Date: 11/25/12
 */
public abstract class BaseInstrumentingBuilder extends ClassProcessingBuilder {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.jps.incremental.instrumentation.BaseInstrumentingBuilder");
  // every instance of builder must have its own marker!
  private final Key<Boolean> IS_INSTRUMENTED_KEY = Key.create("_instrumentation_marker_" + getPresentableName());

  public BaseInstrumentingBuilder() {
    super(BuilderCategory.CLASS_INSTRUMENTER);
  }

  @Override
  protected final ExitCode performBuild(CompileContext context, ModuleChunk chunk, InstrumentationClassFinder finder, OutputConsumer outputConsumer) {
    ExitCode exitCode = ExitCode.NOTHING_DONE;
    for (CompiledClass compiledClass : outputConsumer.getCompiledClasses().values()) {
      final BinaryContent originalContent = compiledClass.getContent();
      final ClassReader reader = new ClassReader(originalContent.getBuffer(), originalContent.getOffset(), originalContent.getLength());
      final int version = getClassFileVersion(reader);
      if (IS_INSTRUMENTED_KEY.get(compiledClass, Boolean.FALSE) || !canInstrument(compiledClass, version)) {
        // do not instrument the same content twice
        continue;
      }
      final ClassWriter writer = new InstrumenterClassWriter(getAsmClassWriterFlags(version), finder);
      try {
        final BinaryContent instrumented = instrument(context, compiledClass, reader, writer, finder);
        if (instrumented != null) {
          compiledClass.setContent(instrumented);
          finder.cleanCachedData(compiledClass.getClassName());
          IS_INSTRUMENTED_KEY.set(compiledClass, Boolean.TRUE);
          exitCode = ExitCode.OK;
        }
      }
      catch (Throwable e) {
        LOG.info(e);
        final String message = e.getMessage();
        if (message != null) {
          context.processMessage(new CompilerMessage(getPresentableName(), BuildMessage.Kind.ERROR, message, compiledClass.getSourceFile().getPath()));
        }
        else {
          context.processMessage(new CompilerMessage(getPresentableName(), e));
        }
      }
    }
    return exitCode;
  }

  protected abstract boolean canInstrument(CompiledClass compiledClass, int classFileVersion);

  @Nullable
  protected abstract BinaryContent instrument(CompileContext context,
                                              CompiledClass compiled,
                                              ClassReader reader,
                                              ClassWriter writer,
                                              InstrumentationClassFinder finder);

}
