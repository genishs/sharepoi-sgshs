package com.sgshs.sharepoi

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

/**
 * 앱의 안내 대화상자(#18). DialogFragment 라 화면이 다시 만들어져도(언어·글꼴 크기 변경, 프로세스 재시작 등)
 * 떠 있던 대화상자가 그대로 남는다. 버튼을 누르면 [REQUEST_KEY] 로 [Action] 과 [payload] 를 화면에 돌려준다
 * (람다를 넘기지 않으므로 다시 만들어진 화면도 같은 결과를 받는다).
 */
class AppDialog : DialogFragment() {

    /** 버튼이 뜻하는 동작. 화면(MainActivity)이 받아 처리한다. */
    enum class Action {
        NONE,
        ROADVIEW, DIAL,
        REQUEST_PERMISSION, OPEN_APP_SETTINGS,
        RETRY_LOCATE, ENABLE_LOCATION,
        COPY_DIAGNOSTIC, RETRY_MAP,
        RETRY_SEARCH, PICK_CATEGORY,
        SHARE_MAP_PICKED
    }

    /** 버튼 하나: 글자와 누를 때의 동작 */
    class Button(val text: String, val action: Action = Action.NONE)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val payload = args.getBundle(ARG_PAYLOAD) ?: Bundle()
        val builder = AlertDialog.Builder(requireContext())
        args.getString(ARG_TITLE)?.let { builder.setTitle(it) }

        val message = args.getString(ARG_MESSAGE)
        val items = args.getStringArray(ARG_ITEMS)
        when {
            items != null -> builder.setItems(items) { _, which ->
                deliver(actionAt(ARG_ITEMS_ACTION), payload, which)
            }
            message != null && args.getBoolean(ARG_SELECTABLE) -> {
                // 본문을 길게 눌러 고르고 복사할 수 있게(디버그 진단 대화상자)
                val view = TextView(requireContext()).apply {
                    text = message
                    setTextIsSelectable(true)
                    val pad = SearchCatalog.dpToPx(16, resources.displayMetrics.density)
                    setPadding(pad, pad, pad, pad)
                }
                builder.setView(view)
            }
            message != null -> builder.setMessage(message)
        }

        args.getString(ARG_POSITIVE)?.let { text ->
            builder.setPositiveButton(text) { _, _ -> deliver(actionAt(ARG_POSITIVE_ACTION), payload) }
        }
        args.getString(ARG_NEUTRAL)?.let { text ->
            builder.setNeutralButton(text) { _, _ -> deliver(actionAt(ARG_NEUTRAL_ACTION), payload) }
        }
        args.getString(ARG_NEGATIVE)?.let { text ->
            builder.setNegativeButton(text) { _, _ -> deliver(actionAt(ARG_NEGATIVE_ACTION), payload) }
        }
        return builder.create()
    }

    private fun actionAt(key: String): Action =
        requireArguments().getString(key)?.let { name -> Action.entries.firstOrNull { it.name == name } } ?: Action.NONE

    private fun deliver(action: Action, payload: Bundle, which: Int = -1) {
        if (action == Action.NONE) return
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_ACTION to action.name, RESULT_PAYLOAD to payload, RESULT_WHICH to which)
        )
    }

    companion object {
        const val REQUEST_KEY = "app_dialog"
        const val RESULT_ACTION = "action"
        const val RESULT_PAYLOAD = "payload"
        const val RESULT_WHICH = "which"

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_SELECTABLE = "selectable"
        private const val ARG_ITEMS = "items"
        private const val ARG_ITEMS_ACTION = "items_action"
        private const val ARG_POSITIVE = "positive"
        private const val ARG_POSITIVE_ACTION = "positive_action"
        private const val ARG_NEUTRAL = "neutral"
        private const val ARG_NEUTRAL_ACTION = "neutral_action"
        private const val ARG_NEGATIVE = "negative"
        private const val ARG_NEGATIVE_ACTION = "negative_action"
        private const val ARG_PAYLOAD = "payload"

        /**
         * 대화상자를 띄운다. 같은 [tag] 의 대화상자가 떠 있으면 새로 띄운 것으로 바꾼다.
         * 화면 상태가 이미 저장된 뒤(백그라운드)면 띄우지 않고 false 를 돌려준다.
         */
        fun show(
            fm: FragmentManager,
            tag: String,
            title: String?,
            message: String? = null,
            positive: Button? = null,
            negative: Button? = null,
            neutral: Button? = null,
            payload: Bundle? = null,
            items: Array<String>? = null,
            itemsAction: Action = Action.NONE,
            selectable: Boolean = false
        ): Boolean {
            if (fm.isStateSaved) return false
            (fm.findFragmentByTag(tag) as? DialogFragment)?.dismissAllowingStateLoss()
            val dialog = AppDialog()
            dialog.arguments = bundleOf(
                ARG_TITLE to title,
                ARG_MESSAGE to message,
                ARG_SELECTABLE to selectable,
                ARG_ITEMS to items,
                ARG_ITEMS_ACTION to itemsAction.name,
                ARG_POSITIVE to positive?.text,
                ARG_POSITIVE_ACTION to positive?.action?.name,
                ARG_NEUTRAL to neutral?.text,
                ARG_NEUTRAL_ACTION to neutral?.action?.name,
                ARG_NEGATIVE to negative?.text,
                ARG_NEGATIVE_ACTION to negative?.action?.name,
                ARG_PAYLOAD to payload
            )
            dialog.show(fm, tag)
            return true
        }

        fun isShowing(fm: FragmentManager, tag: String): Boolean = fm.findFragmentByTag(tag) != null

        /**
         * [tag] 대화상자를 지금 바로 없앤다(대기 중인 제거 트랜잭션까지 반영). recreate() 직전에 부르면
         * 눌린 대화상자가 상태로 저장돼 새 화면에 되살아나지 않는다. 상태가 이미 저장됐으면 아무것도 하지 않는다.
         */
        fun dismissNow(fm: FragmentManager, tag: String) {
            if (fm.isStateSaved) return
            fm.executePendingTransactions()
            val fragment = fm.findFragmentByTag(tag) ?: return
            if (fragment is DialogFragment) fragment.dismissNow()
            fm.findFragmentByTag(tag)?.let { fm.beginTransaction().remove(it).commitNow() }
        }
    }
}
