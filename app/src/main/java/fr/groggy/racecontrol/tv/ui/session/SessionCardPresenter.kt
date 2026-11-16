package fr.groggy.racecontrol.tv.ui.session

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.progress.WatchProgressStore
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.ui.season.browse.Session
import java.util.Locale
import javax.inject.Inject

/** Session card of the redesign: rounded picture, white outline on focus, title and kind, progress of a replay. */
class SessionCardPresenter @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val watchProgressStore: WatchProgressStore
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.session_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val view = viewHolder.view
        val session = item as SessionCard
        val context = view.context
        val live = isLive(session.contentSubtype)

        view.findViewById<TextView>(R.id.card_title).text = session.name
        view.findViewById<TextView>(R.id.card_subtitle).text = kindLabel(context, session.contentSubtype)
        view.findViewById<View>(R.id.card_badge).visibility = if (live) View.VISIBLE else View.GONE

        val progress = (session as? Session)?.let { watchProgressStore.forContent(it.contentId) }
        val track = view.findViewById<View>(R.id.card_progress_track)
        val bar = view.findViewById<View>(R.id.card_progress)
        if (progress != null && !live) {
            track.visibility = View.VISIBLE
            track.post {
                bar.layoutParams = bar.layoutParams.apply { width = (track.width * progress.fraction).toInt() }
            }
        } else {
            track.visibility = View.GONE
        }

        val image = view.findViewById<ImageView>(R.id.card_image)
        if (settingsRepository.getCurrent().displayThumbnailsEnabled) {
            val radius = (10 * context.resources.displayMetrics.density).toInt()
            Glide.with(context)
                .load(session.thumbnail?.url)
                .transform(CenterCrop(), RoundedCorners(radius))
                .into(image)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val image = viewHolder.view.findViewById<ImageView>(R.id.card_image)
        Glide.with(viewHolder.view.context).clear(image)
        image.setImageDrawable(null)
    }

    companion object {
        fun isLive(contentSubtype: String): Boolean = contentSubtype.uppercase(Locale.ROOT) == "LIVE"

        /** "Live", "Replay" or "Show" instead of F1's raw subtype. */
        fun kindLabel(context: Context, contentSubtype: String): String = when (contentSubtype.uppercase(Locale.ROOT)) {
            "LIVE" -> context.getString(R.string.session_kind_live)
            "REPLAY" -> context.getString(R.string.session_kind_replay)
            "" -> ""
            else -> if ("SHOW" in contentSubtype.uppercase(Locale.ROOT)) {
                context.getString(R.string.session_kind_show)
            } else {
                contentSubtype.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }
    }
}

interface SessionCard {

    val name: String
    val contentSubtype: String
    val thumbnail: Image?

    interface Image {
        val url: Uri
    }

}
