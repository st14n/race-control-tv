package fr.groggy.racecontrol.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.progress.WatchProgressStore
import fr.groggy.racecontrol.tv.ui.season.browse.Session
import fr.groggy.racecontrol.tv.ui.session.SessionCardPresenter

/** A replay to continue: where it was left, with its session and weekend. */
data class ContinueItem(
    val session: Session,
    val eventName: String,
    val progress: WatchProgressStore.Entry
)

/** Top of the home screen: the newest weekend's session, big, and "Continue watching" next to it. */
class HeroRow(
    val session: Session,
    val eventName: String,
    val newWeekend: Boolean,
    val continueItems: List<ContinueItem>
) : Row()

class HeroRowPresenter(
    private val showThumbnails: () -> Boolean,
    private val onSessionClicked: (Session) -> Unit,
    private val onContinueClicked: (ContinueItem) -> Unit
) : RowPresenter() {

    init {
        headerPresenter = null
        selectEffectEnabled = false
    }

    override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.home_hero_row, parent, false)
        return ViewHolder(view)
    }

    override fun isUsingDefaultSelectEffect(): Boolean = false

    override fun onBindRowViewHolder(vh: ViewHolder, item: Any) {
        super.onBindRowViewHolder(vh, item)
        val row = item as HeroRow
        val view = vh.view
        val context = view.context
        val session = row.session
        val live = SessionCardPresenter.isLive(session.contentSubtype)

        view.findViewById<View>(R.id.hero_badge_live).visibility = if (live) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.hero_badge_kind).apply {
            val kind = SessionCardPresenter.kindLabel(context, session.contentSubtype)
            text = kind
            visibility = if (live || kind.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.hero_kicker).text = context.getString(
            if (row.newWeekend) R.string.home_hero_new_weekend else R.string.home_hero_weekend,
            row.eventName
        )
        view.findViewById<TextView>(R.id.hero_title).text = session.name
        view.findViewById<TextView>(R.id.hero_play).setText(
            if (live) R.string.home_hero_watch_live else R.string.home_hero_watch
        )
        val heroImage = view.findViewById<ImageView>(R.id.hero_image)
        if (showThumbnails()) {
            val radius = (16 * context.resources.displayMetrics.density).toInt()
            Glide.with(context)
                .load(session.largePictureUrl.ifBlank { session.thumbnail?.url?.toString() })
                .transform(CenterCrop(), RoundedCorners(radius))
                .into(heroImage)
        }
        view.findViewById<View>(R.id.hero_card).setOnClickListener { onSessionClicked(session) }

        val column = view.findViewById<View>(R.id.continue_column)
        val list = view.findViewById<LinearLayout>(R.id.continue_items)
        list.removeAllViews()
        column.visibility = if (row.continueItems.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(context)
        row.continueItems.take(3).forEach { entry ->
            val itemView = inflater.inflate(R.layout.home_continue_item, list, false)
            itemView.findViewById<TextView>(R.id.continue_title).text = "${entry.eventName} · ${entry.session.name}"
            val left = formatRemaining(context, entry.progress.remainingMs)
            itemView.findViewById<TextView>(R.id.continue_subtitle).text = entry.progress.feedLabel
                ?.let { context.getString(R.string.home_continue_left, left, it) }
                ?: context.getString(R.string.home_continue_left_nofeed, left)
            val image = itemView.findViewById<ImageView>(R.id.continue_image)
            if (showThumbnails()) {
                val radius = (8 * context.resources.displayMetrics.density).toInt()
                Glide.with(context).load(entry.session.thumbnail?.url)
                    .transform(CenterCrop(), RoundedCorners(radius)).into(image)
            }
            val bar = itemView.findViewById<View>(R.id.continue_progress)
            val widthPx = (78 * context.resources.displayMetrics.density * entry.progress.fraction).toInt()
            bar.layoutParams = bar.layoutParams.apply { width = widthPx }
            itemView.setOnClickListener { onContinueClicked(entry) }
            list.addView(itemView)
        }
    }

    override fun onUnbindRowViewHolder(vh: ViewHolder) {
        val view = vh.view
        Glide.with(view.context).clear(view.findViewById<ImageView>(R.id.hero_image))
        view.findViewById<LinearLayout>(R.id.continue_items).removeAllViews()
        super.onUnbindRowViewHolder(vh)
    }

    private fun formatRemaining(context: android.content.Context, ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60) {
            context.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
        } else {
            context.getString(R.string.duration_minutes, minutes)
        }
    }
}
