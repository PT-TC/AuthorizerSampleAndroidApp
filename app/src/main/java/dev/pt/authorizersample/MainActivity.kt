package dev.pt.authorizersample

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var baseUrlInput: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrlInput = EditText(this).apply { setText(DEFAULT_BASE_URL) }
        output = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        val signIn = Button(this).apply {
            text = "Sign in with Authorizer (Custom Tab)"
            setOnClickListener { startAuth() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { text = "Authorizer base URL (host = passkey RP ID)" })
            addView(baseUrlInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(signIn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(output, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        intent?.let { handleRedirect(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun startAuth() {
        val base = baseUrlInput.text.toString().trim().trimEnd('/')
        val verifier = randomString(48)
        val state = randomString(16)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
        pending = Pending(base, verifier, state, redirectUri)
        thread { listenForRedirect(server, pending!!) }
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val uri = Uri.parse("$base/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", "openid profile email")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        output.text = "Opening $uri"
        CustomTabsIntent.Builder().build().launchUrl(this, uri)
    }

    private fun handleRedirect(intent: Intent) {
        // authorizersample://done only brings the app back to the foreground.
    }

    private fun listenForRedirect(server: ServerSocket, p: Pending) {
        try {
            server.soTimeout = 300_000
            server.use { srv ->
                srv.accept().use { sock ->
                    val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                    val data = Uri.parse("http://127.0.0.1" + line.split(" ").getOrElse(1) { "/" })
                    val html = "<html><body style='font-family:sans-serif;text-align:center;margin-top:20%'>" +
                        "<h2>Done</h2><a style='font-size:24px' href='authorizersample://done'>Return to app</a>" +
                        "<script>setTimeout(function(){location.href='authorizersample://done'},300)</script></body></html>"
                    sock.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n" + html).toByteArray())
                        flush()
                    }
                    val error = data.getQueryParameter("error")
                    val code = data.getQueryParameter("code")
                    when {
                        error != null -> show("Error: $error ${data.getQueryParameter("error_description").orEmpty()}")
                        data.getQueryParameter("state") != p.state -> show("State mismatch")
                        code == null -> show("No code in redirect: $data")
                        else -> {
                            show("Got code, exchanging…")
                            exchange(p, code)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            show("Redirect listener failed: $e")
        }
    }

    private fun show(text: String) = runOnUiThread { output.text = text }

    private fun exchange(p: Pending, code: String) {
        val result = try {
            val body = listOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to CLIENT_ID,
                "redirect_uri" to p.redirectUri,
                "code_verifier" to p.verifier,
            ).joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
            val conn = (URL("${p.base}/oauth/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(body.toByteArray()) }
            }
            val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream).bufferedReader().readText()
            val json = JSONObject(text)
            if (json.has("id_token")) {
                val claims = String(Base64.decode(json.getString("id_token").split(".")[1], Base64.URL_SAFE))
                "SIGNED IN ✅\n\nid_token claims:\n${JSONObject(claims).toString(2)}"
            } else {
                "HTTP ${conn.responseCode}\n$text"
            }
        } catch (e: Exception) {
            "Token exchange failed: $e"
        }
        runOnUiThread { output.text = result }
    }

    private fun randomString(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private data class Pending(val base: String, val verifier: String, val state: String, val redirectUri: String)

    private companion object {
        const val CLIENT_ID = "local-passkey-poc"
        const val DEFAULT_BASE_URL = "http://localhost:8080"
        var pending: Pending? = null
    }
}
