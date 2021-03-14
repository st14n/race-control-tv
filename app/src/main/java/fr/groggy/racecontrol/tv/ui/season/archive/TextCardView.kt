package fr.groggy.racecontrol.tv.ui.season.archive

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.leanback.widget.BaseCardView
import fr.groggy.racecontrol.tv.R

class TextCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.style.TextCardStyle
): BaseCardView(
    context,
    attrs,
    defStyle
) {
    private val textView: TextView

    init {
        LayoutInflater.from(getContext())
            .inflate(R.layout.item_text_card, this)
        isFocusable = true

        textView = findViewById(R.id.text_view)
    }

    fun setText(text: CharSequence?) {
        textView.text = text
    }
}
