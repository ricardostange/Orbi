package br.com.assistente.teste

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** A dedicated browser entry point; forward the original URI without logging OAuth secrets. */
class SpotifyCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = intent.data
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
