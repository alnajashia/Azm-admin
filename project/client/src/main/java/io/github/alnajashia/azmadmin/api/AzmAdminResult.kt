package io.github.alnajashia.azmadmin.api

import android.os.Parcel
import android.os.Parcelable

/**
 * Typed result of a privileged operation, returned by [IAzmAdminService].
 * [success] is only ever set after the server post-verified the operation.
 * [exitCode] carries the exit code of the decisive shell step, or -1 when no
 * shell step decided the outcome (direct binder path / not applicable).
 */
data class AzmAdminResult(
    val success: Boolean,
    val code: String,
    val message: String,
    val detail: String,
    val exitCode: Int,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        success = parcel.readByte().toInt() != 0,
        code = parcel.readString() ?: "",
        message = parcel.readString() ?: "",
        detail = parcel.readString() ?: "",
        exitCode = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (success) 1 else 0)
        parcel.writeString(code)
        parcel.writeString(message)
        parcel.writeString(detail)
        parcel.writeInt(exitCode)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AzmAdminResult> {
        override fun createFromParcel(parcel: Parcel): AzmAdminResult = AzmAdminResult(parcel)
        override fun newArray(size: Int): Array<AzmAdminResult?> = arrayOfNulls(size)

        fun ok(code: String, message: String, detail: String = "", exitCode: Int = -1) =
            AzmAdminResult(true, code, message, detail, exitCode)

        fun fail(code: String, message: String, detail: String = "", exitCode: Int = -1) =
            AzmAdminResult(false, code, message, detail, exitCode)
    }
}
