package com.orbion.vpn

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.orbion.vpn.motor.Falhas
import java.io.File
import java.io.FileNotFoundException

/**
 * Entrega o relatório para o suporte a outro app (WhatsApp, e-mail, Drive...)
 * pelo menu "Compartilhar" do Android.
 *
 * Só dá acesso de leitura, só a arquivos .txt da pasta de relatórios, e só
 * ao app que a pessoa escolher no menu (permissão temporária do Android).
 * Não é exportado: nenhum outro app abre nada aqui por conta própria.
 */
class Arquivos : ContentProvider() {

    companion object {
        const val AUTORIDADE = "com.orbion.vpn.arquivos"
        private const val PASTA = "relatorios"

        fun pasta(context: Context): File = File(context.cacheDir, PASTA).apply { mkdirs() }

        fun uri(nome: String): Uri = Uri.parse("content://$AUTORIDADE/$PASTA/$nome")
    }

    override fun onCreate(): Boolean {
        // Primeira coisa do processo da tela: guarda o rastro de qualquer erro
        // não tratado (vai para o relatório do suporte).
        context?.let { Falhas.instalar(it, "tela", conterNoFundo = true) }
        return true
    }

    private fun arquivo(uri: Uri): File? {
        val ctx = context ?: return null
        val partes = uri.pathSegments
        if (partes.size != 2 || partes[0] != PASTA) return null
        val nome = partes[1]
        if (nome.startsWith(".") || '/' in nome || '\\' in nome || !nome.endsWith(".txt")) return null
        val f = File(pasta(ctx), nome)
        return if (f.isFile) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Somente leitura.")
        val f = arquivo(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = arquivo(uri) ?: return null
        val colunas = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(colunas)
        cursor.addRow(
            colunas.map<String, Any?> {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            }.toTypedArray(),
        )
        return cursor
    }

    override fun getType(uri: Uri): String = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
