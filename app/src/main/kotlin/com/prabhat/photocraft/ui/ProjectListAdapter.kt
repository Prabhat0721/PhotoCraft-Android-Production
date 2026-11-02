package com.prabhat.photocraft.ui

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.prabhat.photocraft.R
import com.prabhat.photocraft.project.Project

class ProjectListAdapter(
    private val context: Context,
    private val onOpen: (Project) -> Unit,
    private val onDelete: (Project) -> Unit
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val projects = ArrayList<Project>()

    fun submit(items: List<Project>) {
        projects.clear()
        projects.addAll(items)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = projects.size

    override fun getItem(position: Int): Project = projects[position]

    override fun getItemId(position: Int): Long = projects[position].id.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView
            ?: inflater.inflate(R.layout.item_project, parent, false)
        val holder = view.tag as? Holder ?: Holder(view).also { view.tag = it }
        val project = getItem(position)
        holder.name.text = project.displayName
        val relative = DateUtils.getRelativeTimeSpanString(
            project.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
        val meta = if (project.width > 0 && project.height > 0) {
            context.getString(
                R.string.project_item_meta,
                project.width,
                project.height,
                relative
            )
        } else {
            context.getString(R.string.project_item_meta_unknown, relative)
        }
        holder.meta.text = if (project.isExported) {
            context.getString(
                R.string.project_item_meta_suffix,
                meta,
                context.getString(R.string.project_item_exported)
            )
        } else {
            meta
        }
        val thumbnail = project.thumbnailFile
        if (thumbnail != null && thumbnail.isFile) {
            holder.thumbnail.setImageURI(Uri.fromFile(thumbnail))
        } else {
            holder.thumbnail.setImageResource(R.drawable.ic_mark)
        }
        holder.delete.contentDescription =
            context.getString(R.string.project_library_delete) + ": " + project.displayName
        holder.delete.setOnClickListener { onDelete(project) }
        view.setOnClickListener { onOpen(project) }
        view.setOnLongClickListener {
            onDelete(project)
            true
        }
        return view
    }

    private class Holder(view: View) {
        val thumbnail: ImageView = view.findViewById(R.id.projectThumbnail)
        val name: TextView = view.findViewById(R.id.projectName)
        val meta: TextView = view.findViewById(R.id.projectMeta)
        val delete: ImageButton = view.findViewById(R.id.deleteProjectButton)
    }
}
