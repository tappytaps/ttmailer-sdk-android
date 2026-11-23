package com.tappytaps.ttmailer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP answer. */
class HttpResponse(val code: Int, val body: String)

/** Sends one HTTP request (HttpURLConnection in the app, a fake in tests). Throws IOException without an answer. */
interface HttpTransport {
    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

internal class UrlConnectionTransport : HttpTransport {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.useCaches = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(code, text)
        } finally {
            c.disconnect()
        }
    }
}
