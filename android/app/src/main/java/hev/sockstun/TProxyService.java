package hev.sockstun;

/**
 * Ponte JNI com a biblioteca libhev-socks5-tunnel.so.
 *
 * A biblioteca é compilada pela esteira do GitHub com
 * PKGNAME=hev/sockstun e CLSNAME=TProxyService. Na carga, ela procura
 * EXATAMENTE esta classe e registra as quatro funções abaixo. Se o nome do
 * pacote, da classe ou de qualquer assinatura mudar, a carga falha.
 *
 * (A versão antiga do Orbion trazia uma biblioteca compilada para outra
 * classe, e por isso precisava tentar três variantes. Agora há uma só.)
 */
public final class TProxyService {

    private TProxyService() {
    }

    /** Sobe o túnel numa thread nativa. Retorna logo. */
    public static native boolean TProxyStartService(String configPath, int fd);

    /** Para o túnel e espera a thread nativa terminar. */
    public static native boolean TProxyStopService();

    /** Diz se o túnel nativo está rodando. */
    public static native boolean TProxyIsRunning();

    /** [pacotes enviados, bytes enviados, pacotes recebidos, bytes recebidos] */
    public static native long[] TProxyGetStats();
}
