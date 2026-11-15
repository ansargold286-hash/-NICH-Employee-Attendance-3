package com.example.data.report

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

data class SmtpConfig(
    val host: String = "smtp.gmail.com",
    val port: Int = 465,
    val useSsl: Boolean = true,
    val username: String,
    val appPassword: String,
    val connectTimeoutMs: Int = 12_000,
    val readTimeoutMs: Int = 15_000
)

fun interface SmtpSocketConnector {
    fun connect(config: SmtpConfig): Socket
}

object DefaultSmtpSocketConnector : SmtpSocketConnector {
    override fun connect(config: SmtpConfig): Socket {
        val socket = if (config.useSsl) {
            SSLSocketFactory.getDefault().createSocket()
        } else {
            Socket()
        }
        socket.soTimeout = config.readTimeoutMs
        socket.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
        return socket
    }
}

class SmtpEmailSender(
    private val socketConnector: SmtpSocketConnector = DefaultSmtpSocketConnector
) {
    fun sendWithAttachment(
        config: SmtpConfig,
        toEmail: String,
        subject: String,
        plainTextBody: String,
        attachmentFile: File
    ) {
        val cleanPassword = config.appPassword.replace(" ", "").trim()
        require(config.username.isNotBlank()) { "SMTP sender email must not be blank" }
        require(cleanPassword.isNotBlank()) { "SMTP app password must not be blank" }
        require(toEmail.isNotBlank()) { "Recipient email must not be blank" }
        require(attachmentFile.exists() && attachmentFile.length() > 0L) {
            "PDF attachment file is missing or empty"
        }

        val attachmentBytes = attachmentFile.readBytes()
        val mimePayload = buildMimeMessage(
            fromEmail = config.username.trim(),
            toEmail = toEmail.trim(),
            subject = subject,
            plainTextBody = plainTextBody,
            attachmentName = attachmentFile.name,
            attachmentBytes = attachmentBytes
        )

        socketConnector.connect(config).use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            expectCode(readSmtpResponse(input), 220, "SMTP greeting")

            sendLine(output, "EHLO nich-terminal")
            expectCode(readSmtpResponse(input), 250, "EHLO")

            sendLine(output, "AUTH LOGIN")
            expectCode(readSmtpResponse(input), 334, "AUTH LOGIN")

            sendLine(output, encodeBase64SingleLine(config.username.trim()))
            expectCode(readSmtpResponse(input), 334, "AUTH username")

            sendLine(output, encodeBase64SingleLine(cleanPassword))
            expectCode(readSmtpResponse(input), 235, "AUTH password")

            sendLine(output, "MAIL FROM:<${config.username.trim()}>")
            expectCode(readSmtpResponse(input), 250, "MAIL FROM")

            sendLine(output, "RCPT TO:<${toEmail.trim()}>")
            val rcptReply = readSmtpResponse(input)
            if (rcptReply.code != 250 && rcptReply.code != 251) {
                throw IOException("SMTP RCPT TO rejected (${rcptReply.code}): ${rcptReply.message}")
            }

            sendLine(output, "DATA")
            expectCode(readSmtpResponse(input), 354, "DATA")

            output.write(mimePayload.toByteArray(StandardCharsets.UTF_8))
            output.write("\r\n.\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            expectCode(readSmtpResponse(input), 250, "DATA body")

            runCatching {
                sendLine(output, "QUIT")
                readSmtpResponse(input)
            }
        }
    }

    internal fun buildMimeMessage(
        fromEmail: String,
        toEmail: String,
        subject: String,
        plainTextBody: String,
        attachmentName: String,
        attachmentBytes: ByteArray
    ): String {
        val boundary = "====NICH_ATT_${UUID.randomUUID().toString().replace("-", "")}===="
        val encodedSubject = "=?UTF-8?B?${encodeBase64SingleLine(subject)}?="
        val encodedBody = Base64.encodeToString(
            plainTextBody.toByteArray(StandardCharsets.UTF_8),
            Base64.CRLF
        ).trimEnd('\r', '\n')
        val encodedPdf = Base64.encodeToString(attachmentBytes, Base64.CRLF).trimEnd('\r', '\n')

        return buildString {
            append("From: NICH Employee Attendance 3 <").append(fromEmail).append(">\r\n")
            append("To: <").append(toEmail).append(">\r\n")
            append("Subject: ").append(encodedSubject).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n")
            append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(encodedBody).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/pdf; name=\"").append(attachmentName).append("\"\r\n")
            append("Content-Disposition: attachment; filename=\"").append(attachmentName).append("\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(encodedPdf).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
    }

    private fun encodeBase64SingleLine(text: String): String {
        return Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    private fun sendLine(output: BufferedOutputStream, line: String) {
        output.write(line.toByteArray(StandardCharsets.UTF_8))
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private data class SmtpReply(val code: Int, val message: String)

    private fun readSmtpResponse(input: BufferedInputStream): SmtpReply {
        val lines = mutableListOf<String>()
        var code = -1
        while (true) {
            val line = readAsciiLine(input) ?: throw IOException("Unexpected EOF from SMTP server")
            lines += line
            if (line.length >= 3 && line.substring(0, 3).all { it.isDigit() }) {
                code = line.substring(0, 3).toInt()
                if (line.length == 3 || line[3] == ' ') {
                    break
                }
            } else {
                break
            }
        }
        return SmtpReply(code = code, message = lines.joinToString(" | "))
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            b = input.read()
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun expectCode(reply: SmtpReply, expectedCode: Int, stage: String) {
        if (reply.code != expectedCode) {
            throw IOException("SMTP $stage failed (${reply.code}): ${reply.message}")
        }
    }
}
