package fr.groggy.racecontrol.tv.ui.settings

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import fr.groggy.racecontrol.tv.R

class DonationDialog: DialogFragment(R.layout.fragment_dialog_donation) {
    companion object {
        private val TAG = "${DonationDialog::class.qualifiedName}.DONATION"

        fun show(fragmentManager: FragmentManager) {
            DonationDialog().show(fragmentManager, TAG)
        }
    }
}