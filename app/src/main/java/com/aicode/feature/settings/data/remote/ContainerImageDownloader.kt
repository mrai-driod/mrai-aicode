package com.aicode.feature.settings.data.remote

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an image rootfs into the app-private directory filesDir/rootfs_images/ (the same directory as copies of manually imported images),
 * returns a file:// uri, which [com.aicode.feature.agent.domain.container.ContainerInstaller] then extracts.
 * When the coroutine is cancelled it synchronously cancels the underlying call and cleans up the partial file as well.
 */
@Singleton
class ContainerImageDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
            .build()
    }

    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "rootfs_images").apply { mkdirs() }
        val dest = File(dir, fileName)
        val call = client.newCall(Request.Builder().url(url).build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Response body is empty")
                        val total = body.contentLength()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = 0L
                        body.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    read += n
                                    onProgress(read, total)
                                }
                            }
                        }
                        cont.resume(Uri.fromFile(dest).toString())
                    } catch (e: Exception) {
                        dest.delete()
                        if (cont.isCancelled) return
                        cont.resumeWithException(e)
                    }
                }
            })
        }
    }
}
