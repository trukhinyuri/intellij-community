// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.service.project.open

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.externalSystem.action.DetachExternalProjectAction.detachProject
import com.intellij.openapi.externalSystem.importing.AbstractOpenProjectProvider
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.internal.InternalExternalProjectInfo
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.service.ui.ExternalProjectDataSelectorDialog
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants.BUILD_FILE_EXTENSIONS
import org.jetbrains.plugins.gradle.util.GradleConstants.SYSTEM_ID
import org.jetbrains.plugins.gradle.util.updateGradleJvm
import org.jetbrains.plugins.gradle.util.validateJavaHome

internal class GradleOpenProjectProvider : AbstractOpenProjectProvider() {
  override val systemId = SYSTEM_ID

  override fun isProjectFile(file: VirtualFile): Boolean {
    return !file.isDirectory && BUILD_FILE_EXTENSIONS.any { file.name.endsWith(it) }
  }

  override suspend fun linkProject(projectFile: VirtualFile, project: Project) {
    LOG.debug("Link Gradle project '$projectFile' to existing project ${project.name}")

    val projectPath = getProjectDirectory(projectFile).toNioPath()

    if (!ExternalSystemTrustedProjectDialog.confirmLinkingUntrustedProjectAsync(project, systemId, projectPath)) {
      return
    }

    val settings = createLinkSettings(projectPath, project)

    validateJavaHome(project, projectPath, settings.resolveGradleVersion())

    val externalProjectPath = settings.externalProjectPath
    ExternalSystemApiUtil.getSettings(project, SYSTEM_ID).linkProject(settings)

    if (!Registry.`is`("external.system.auto.import.disabled")) {
      ExternalProjectsManagerImpl.getInstance(project).runWhenInitialized {
        val importSpec = ImportSpecBuilder(project, SYSTEM_ID)
        if (!TrustedProjects.isProjectTrusted(project)) {
          importSpec.usePreviewMode()
        }
        importSpec.callback(createFinalImportCallback(project, externalProjectPath))
        ExternalSystemUtil.refreshProject(externalProjectPath, importSpec)
      }
    }
  }

  override suspend fun unlinkProject(project: Project, externalProjectPath: String) {
    val projectData = ExternalSystemApiUtil.findProjectNode(project, systemId, externalProjectPath)?.data ?: return
    withContext(Dispatchers.EDT) {
      detachProject(project, projectData.owner, projectData, null)
    }
  }

  private fun createFinalImportCallback(project: Project, externalProjectPath: String): ExternalProjectRefreshCallback {
    return object : ExternalProjectRefreshCallback {
      override fun onSuccess(externalProject: DataNode<ProjectData>?) {
        if (externalProject == null) return
        selectDataToImport(project, externalProjectPath, externalProject)
        importData(project, externalProject)
        updateGradleJvm(project, externalProjectPath)
      }
    }
  }

  private fun selectDataToImport(project: Project, externalProjectPath: String, externalProject: DataNode<ProjectData>) {
    val settings = GradleSettings.getInstance(project)
    val showSelectiveImportDialog = settings.showSelectiveImportDialogOnInitialImport()
    val application = ApplicationManager.getApplication()
    if (showSelectiveImportDialog && !application.isHeadlessEnvironment) {
      application.invokeAndWait {
        val projectInfo = InternalExternalProjectInfo(SYSTEM_ID, externalProjectPath, externalProject)
        val dialog = ExternalProjectDataSelectorDialog(project, projectInfo)
        if (dialog.hasMultipleDataToSelect()) {
          dialog.showAndGet()
        }
        else {
          Disposer.dispose(dialog.disposable)
        }
      }
    }
  }

  private fun importData(project: Project, externalProject: DataNode<ProjectData>) {
    ProjectDataManager.getInstance().importData(externalProject, project)
  }
}