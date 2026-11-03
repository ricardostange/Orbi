package br.com.assistente.teste.integrations.spotify

/** Map known server codes to useful guidance without exposing response bodies or tokens. */
internal object SpotifyErrors {
    fun authorization(status: Int, reason: String): String = when (reason) {
        "invalid_client" -> "Client ID recusado pelo Spotify. Confira o Client ID em Configuração do Spotify; não use o Client Secret."
        "invalid_grant" -> "Autorização expirada ou revogada. Conecte novamente e conclua o login em até dez minutos."
        "invalid_scope" -> "Permissões recusadas pelo Spotify. Confira se Web API está habilitada no painel do app."
        "invalid_request" -> "Configuração OAuth recusada. Confira se o Redirect URI cadastrado é exatamente ${REDIRECT_URI}."
        else -> "Não foi possível autorizar o Spotify (HTTP $status). Tente conectar novamente."
    }
    fun api(status: Int, reason: String, retryAfter: String?): String = when (status) {
        401 -> "Sessão Spotify inválida. Conecte novamente."
        403 -> when (reason) {
            "PREMIUM_REQUIRED" -> "Este comando exige Spotify Premium na conta conectada."
            "RESTRICTION_VIOLATED" -> "O dispositivo recusou este comando. Abra o Spotify e verifique o dispositivo de reprodução."
            else -> "Spotify recusou o acesso. No painel do app, confira a conta em Users and Access e os requisitos de Premium do modo de desenvolvimento."
        }
        404 -> "Nenhum dispositivo ativo ou recurso não encontrado. Abra o Spotify, inicie uma música e teste a conexão novamente."
        429 -> "Limite do Spotify atingido. Aguarde ${retryAfter?.toIntOrNull()?.takeIf { it >= 0 }?.toString() ?: "alguns"} segundos."
        else -> "Spotify indisponível (HTTP $status). Não repita alterações sem conferir o resultado."
    }
    private const val REDIRECT_URI = "orbi://callback"
}
