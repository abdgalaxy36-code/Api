package com.ruh.flight.live

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ruh.flight.live.databinding.ItemFlightBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FlightAdapter(
    private val onClick: (LiveFlight) -> Unit
) : RecyclerView.Adapter<FlightAdapter.Holder>() {

    private var items: List<LiveFlight> = emptyList()

    fun submit(list: List<LiveFlight>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemFlightBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(items[position])

    inner class Holder(private val b: ItemFlightBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(f: LiveFlight) {
            bindRow(b, f, onClick)
            b.root.setOnClickListener { onClick(f) }
        }
    }

    companion object {
        /** Shared by the list adapter and the Stats "today's flights" section. */
        fun bindRow(
            b: ItemFlightBinding,
            f: LiveFlight,
            onClick: (LiveFlight) -> Unit,
            index: Int = 0
        ) {
            val tz = ZoneId.systemDefault()
            val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(tz)

            // numbered position in the opened list: Flight 1, Flight 2, …
            b.number.text = if (index > 0) "Flight $index  ·  ${f.number}" else f.number
            b.airline.text = f.airline.ifBlank { "—" }
            b.place.text = buildString {
                if (f.otherCity.isNotBlank()) append(f.otherCity)
                if (f.otherIata.isNotBlank()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(f.otherIata)
                }
                if (isEmpty()) append("—")
            }

            val extra = buildList {
                if (f.terminal.isNotBlank()) add("T" + f.terminal)
                if (f.gate.isNotBlank()) add("Gate " + f.gate)
                if (f.aircraft.isNotBlank()) add(f.aircraft)
                if (f.live) add("LIVE")
            }
            b.meta.text = extra.joinToString("   ").ifBlank { "—" }
            b.meta.alpha = if (f.live) 1f else 0.6f

            // The badge colour is delay severity, derived from the actual and
            // scheduled times so live and historic rows look the same.
            val color = StatusColor.of(f.scheduledEpoch, f.realEpoch, f.statusColor)
            val time = if (f.realEpoch > 0L) fmt.format(Instant.ofEpochSecond(f.realEpoch)) else "—"

            // FR24 puts the status word and the time inside the coloured pill.
            b.statusBox.text = f.statusWord + "  " + time
            b.statusBox.setTextColor(Color.WHITE)
            b.statusBox.setBackgroundColor(colorFor(color))
            b.statusBox.gravity = Gravity.CENTER
            b.statusBox.typeface = Typeface.DEFAULT_BOLD

            b.statusMeaning.text = StatusColor.meaning(color)
            b.statusMeaning.setTextColor(colorFor(color))

            b.root.setOnClickListener { onClick(f) }
        }

        fun colorFor(name: String): Int = when (name.lowercase()) {
            StatusColor.RED -> Color.parseColor("#D32F2F")
            StatusColor.YELLOW -> Color.parseColor("#F57C00")
            StatusColor.GREEN -> Color.parseColor("#2E7D32")
            else -> Color.parseColor("#546E7A")
        }
    }
}
