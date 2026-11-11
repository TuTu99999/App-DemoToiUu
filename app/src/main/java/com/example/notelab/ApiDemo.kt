package com.example.notelab

import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** CASE 5: network I/O runs on IO, callbacks resume on Main. Owned by the Activity. */
class ApiDemo(private val log: Consumer<String>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun fetch(address: String, result: Consumer<String>, finished: Runnable) {
        scope.launch {
            val start = SystemClock.elapsedRealtime()
            try {
                log.accept("API · bắt đầu coroutine")
                val message = withContext(Dispatchers.IO) {
                    log.accept("API · đang gọi HTTP, chờ server")
                    val url = URL(address)
                    require(url.protocol == "http" || url.protocol == "https") { "Dùng URL http hoặc https" }
                    val connection = url.openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 5000
                        connection.readTimeout = 15000
                        connection.useCaches = false
                        val code = connection.responseCode
                        if (code !in 200..299) throw IOException("HTTP $code")
                        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(4097)
                            var count = 0
                            while (count < buffer.size) {
                                val read = reader.read(buffer, count, buffer.size - count)
                                if (read == -1) break
                                count += read
                            }
                            require(count <= 4096) { "Phản hồi vượt quá 4096 ký tự" }
                            String(buffer, 0, count)
                        }
                        JSONObject(text).getString("message")
                    } finally {
                        connection.disconnect()
                    }
                }
                log.accept("API · nhận kết quả, cập nhật UI")
                val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
                result.accept(String.format(Locale.US, "%.1f giây · %s", seconds, message))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                result.accept("Gọi API thất bại · ${error.message}")
                log.accept("API · lỗi ${error.message}")
            } finally {
                if (isActive) finished.run()
            }
        }
    }

    fun close() = scope.cancel()
}
