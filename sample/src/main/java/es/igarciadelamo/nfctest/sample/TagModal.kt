package es.igarciadelamo.nfctest.sample

import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * The colourful modal shown every time a tag is delivered.
 *
 * It is a view inside the activity window rather than a [android.app.Dialog], so
 * it cannot outlive the activity or leak a window during lifecycle changes.
 */
class TagModal(root: View) {

    private val root: View = root
    private val card: View = root.findViewById(R.id.tag_card)
    private val title: TextView = root.findViewById(R.id.tag_title)
    private val source: TextView = root.findViewById(R.id.tag_source)
    private val uid: TextView = root.findViewById(R.id.tag_uid)
    private val recordsLabel: TextView = root.findViewById(R.id.tag_records_label)
    private val records: TextView = root.findViewById(R.id.tag_records)
    private val meta: TextView = root.findViewById(R.id.tag_meta)

    init {
        // Tapping the scrim closes the modal; tapping the card itself does not.
        root.setOnClickListener { hide() }
        card.isClickable = true
        root.findViewById<Button>(R.id.tag_close).setOnClickListener { hide() }
        records.movementMethod = ScrollingMovementMethod()
    }

    fun show(delivery: ReceivedTags.Delivery) {
        val context = root.context

        title.text = when {
            delivery.path == "onCreate" -> context.getString(R.string.modal_title_cold)
            delivery.source == "reader" -> context.getString(R.string.modal_title_reader)
            else -> context.getString(R.string.modal_title_intent)
        }

        source.text = delivery.path?.let { "${delivery.source} · $it" } ?: delivery.source

        uid.text = delivery.uid
            .uppercase()
            .chunked(2)
            .joinToString(" ")
            .ifEmpty { "—" }

        recordsLabel.text = context.getString(R.string.modal_records, delivery.records.size)
        records.text = if (delivery.records.isEmpty()) {
            context.getString(R.string.modal_no_records)
        } else {
            delivery.records.joinToString("\n") { "• $it" }
        }

        meta.text = buildString {
            append("thread: ${delivery.thread}")
            delivery.action?.let { append("\naction: $it") }
            delivery.data?.let { append("\ndata: $it") }
            delivery.type?.let { append("\ntype: $it") }
            append("\ntime: ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(delivery.timestamp))}")
        }

        if (root.visibility == View.VISIBLE) return

        root.alpha = 0f
        card.scaleX = 0.88f
        card.scaleY = 0.88f
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(140).start()
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    fun hide() {
        if (root.visibility != View.VISIBLE) return
        card.animate().cancel()
        root.animate()
            .alpha(0f)
            .setDuration(130)
            .withEndAction {
                root.visibility = View.GONE
                root.alpha = 1f
            }
            .start()
    }
}
