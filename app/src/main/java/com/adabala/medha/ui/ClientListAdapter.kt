package com.adabala.medha.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.adabala.medha.R
import com.adabala.medha.auth.ClientRegistry

/**
 * Backs the RecyclerView in `dialog_clients.xml`.
 *
 * `ListAdapter`/`DiffUtil` rather than a plain `Adapter` with
 * `notifyDataSetChanged()`: `ClientRegistry.Client` is a data class, so
 * structural equality is exactly what [DiffUtil.ItemCallback] needs, and it
 * costs nothing extra to wire correctly the first time. In practice this
 * dialog is always rebuilt fresh from [ClientRegistry.all] rather than
 * updated in place, so the visible difference today is just avoiding a full
 * rebind flicker — but it's the correct default for any list, not a
 * premature optimization for this one.
 *
 * [onRowTapped] is called for both a tap on the row itself and a tap on the
 * overflow button — the caller (MainActivity.showClientsDialog) points both
 * at the same showClientActions function, so there is exactly one code path
 * for "what can I do with this client" regardless of which affordance the
 * person used to get there.
 */
class ClientListAdapter(
    private val onRowTapped: (ClientRegistry.Client) -> Unit
) : ListAdapter<ClientRegistry.Client, ClientListAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val adminBadge: View = view.findViewById(R.id.clientAdminBadge)
        val idText: TextView = view.findViewById(R.id.clientId)
        val capsText: TextView = view.findViewById(R.id.clientCaps)
        val overflow: View = view.findViewById(R.id.clientOverflow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_client, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val client = getItem(position)
        holder.idText.text = client.id
        val caps = if (client.isAdmin) {
            holder.capsText.context.getString(R.string.client_full_access)
        } else {
            client.capabilities.sorted().joinToString(", ")
        }
        holder.capsText.text = holder.capsText.context.getString(
            R.string.client_row_subtitle, client.namespace, caps
        )
        holder.adminBadge.visibility = if (client.isAdmin) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onRowTapped(client) }
        holder.overflow.setOnClickListener { onRowTapped(client) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ClientRegistry.Client>() {
            override fun areItemsTheSame(old: ClientRegistry.Client, new: ClientRegistry.Client) =
                old.id == new.id

            override fun areContentsTheSame(old: ClientRegistry.Client, new: ClientRegistry.Client) =
                old == new
        }
    }
}
