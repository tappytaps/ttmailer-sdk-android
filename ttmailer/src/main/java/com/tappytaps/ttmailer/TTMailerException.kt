package com.tappytaps.ttmailer

/** Why a call the app waits for (subscribe, reset, deleteContact) failed. */
class TTMailerException(val reason: Reason, val status: Int = 0, val code: String? = null) :
    Exception("TTMailer: $reason${if (status > 0) " ($status${code?.let { " $it" } ?: ""})" else ""}") {
    enum class Reason {
        /** TTMailer.configure was not called. */
        NOT_CONFIGURED,
        /** Not a valid e-mail address. */
        INVALID_EMAIL,
        /** The app has no sign-up form in TTMailer (or it is disabled). */
        NO_FORM,
        /** The app's form does not confirm addresses. */
        FORM_WITHOUT_CONFIRMATION,
        /** Too many requests from this network; try later. */
        RATE_LIMITED,
        /** No connection or a timeout. */
        NETWORK,
        /** Any other answer of the server. */
        SERVER,
    }
}
