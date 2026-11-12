@file:Suppress("EnumEntryName")

package com.eyes.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Version du protocole PC ↔ téléphone (voir la spec, section 6.5). */
const val PROTOCOL_VERSION = 1

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
enum class Camera { back, front }

@Serializable
enum class DeniedReason { unknown, revoked, version, bad_code, code_expired, not_pairing }

@Serializable
enum class ErrorCode { busy, camera_busy, permission_missing, bad_message }

@Serializable
sealed interface Msg

@Serializable @SerialName("pair")
data class PairMsg(val code: String, val pcId: String, val pcName: String, val v: Int) : Msg

@Serializable @SerialName("paired")
data class Paired(val token: String, val phoneId: String, val phoneName: String) : Msg

@Serializable @SerialName("hello")
data class Hello(val pcId: String, val token: String, val v: Int) : Msg

@Serializable @SerialName("welcome")
data class Welcome(val phoneId: String, val phoneName: String) : Msg

@Serializable @SerialName("denied")
data class Denied(val reason: DeniedReason) : Msg

@Serializable @SerialName("start")
data class Start(val video: Boolean, val audio: Boolean, val camera: Camera) : Msg

@Serializable @SerialName("offer")
data class Offer(val sdp: String) : Msg

@Serializable @SerialName("answer")
data class Answer(val sdp: String) : Msg

@Serializable @SerialName("set")
data class SetMsg(val video: Boolean? = null, val audio: Boolean? = null, val camera: Camera? = null) : Msg

@Serializable @SerialName("stop")
data object Stop : Msg

@Serializable @SerialName("error")
data class ErrorMsg(val code: ErrorCode) : Msg

object Codec {
    private val json = Json {
        classDiscriminator = "type"
        explicitNulls = false
        isLenient = false
        coerceInputValues = false
    }

    fun decode(raw: String): Msg {
        val msg = try {
            json.decodeFromString(Msg.serializer(), raw)
        } catch (e: SerializationException) {
            throw ProtocolException("message invalide", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("message invalide", e)
        }
        if (msg is SetMsg && msg.video == null && msg.audio == null && msg.camera == null) {
            throw ProtocolException("'set' sans changement")
        }
        return msg
    }

    fun encode(m: Msg): String = json.encodeToString(Msg.serializer(), m)
}
