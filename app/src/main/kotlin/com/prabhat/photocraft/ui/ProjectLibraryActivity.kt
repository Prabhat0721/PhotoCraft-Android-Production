package com.prabhat.photocraft.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import com.prabhat.photocraft.R
import com.prabhat.photocraft.project.Project
import com.prabhat.photocraft.project.ProjectFiles
import com.prabhat.photocraft.project.ProjectStore

class ProjectLibraryActivity : Activity() {
    private lateinit var store: ProjectStore
    private lateinit var adapter: ProjectListAdapter
    private lateinit var listView: ListView
    private lateinit var emptyState: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projects)
        store = ProjectStore(this)
        listView = findViewById(R.id.projectList)
        emptyState = findViewById(R.id.projectEmptyState)
        adapter = ProjectListAdapter(
            context = this,
            onOpen = ::openProject,
            onDelete = ::confirmDelete
        )
        listView.adapter = adapter
        findViewById<ImageButton>(R.id.projectBackButton).setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.newProjectButton).setOnClickListener {
            finishWithNewPhoto()
        }
        findViewById<Button>(R.id.emptyNewProjectButton).setOnClickListener {
            finishWithNewPhoto()
        }
        applyEdgeToEdge()
        loadProjects()
    }

    private fun loadProjects() {
        val projects = store.list()
        adapter.submit(projects)
        val empty = projects.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun openProject(project: Project) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_PROJECT_ID, project.id)
        )
        finish()
    }

    private fun confirmDelete(project: Project) {
        AlertDialog.Builder(this)
            .setTitle(R.string.project_delete_title)
            .setMessage(getString(R.string.project_delete_message, project.displayName))
            .setPositiveButton(R.string.project_delete_confirm) { _, _ ->
                store.delete(project.id)
                ProjectFiles.deleteProject(this, project.id)
                loadProjects()
            }
            .setNegativeButton(R.string.project_delete_cancel, null)
            .show()
    }

    private fun finishWithNewPhoto() {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_NEW_PHOTO, true)
        )
        finish()
    }

    private fun applyEdgeToEdge() {
        val root = findViewById<FrameLayout>(R.id.projectsRoot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_NEW_PHOTO = "new_photo"
    }
}
