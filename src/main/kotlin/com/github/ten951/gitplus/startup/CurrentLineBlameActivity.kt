package com.github.ten951.gitplus.startup

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.github.ten951.gitplus.blame.CurrentLineBlameService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CurrentLineBlameActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) { project.service<CurrentLineBlameService>().start() }
    }
}
