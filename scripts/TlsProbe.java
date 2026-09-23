import javax.net.ssl.*;
import java.util.List;

/**
 * opendart.fss.or.kr TLS 핸드셰이크 진단 (2026-09-18) — 앱(Reactor Netty)이 handshake_failure를 받는데 curl은 정상인 상황에서,
 * 같은 JDK의 순수 SSLSocket으로도 실패하는지 확인한다. Netty 없이 JDK만 사용.
 * 실행: & "D:\jdks\jdk-21.0.12.101-hotspot\bin\java.exe" scripts\TlsProbe.java
 */
public class TlsProbe {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "opendart.fss.or.kr";
        System.out.println("java.version=" + System.getProperty("java.version") + " home=" + System.getProperty("java.home"));
        System.out.println("jdk.tls.client.protocols=" + System.getProperty("jdk.tls.client.protocols")
                + " disabledAlgorithms=" + java.security.Security.getProperty("jdk.tls.disabledAlgorithms"));
        for (String proto : new String[]{"(default)", "TLSv1.2", "TLSv1.3"}) {
            try {
                SSLSocket s = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket(host, 443);
                if (!proto.startsWith("(")) s.setEnabledProtocols(new String[]{proto});
                SSLParameters p = s.getSSLParameters();
                p.setServerNames(List.of(new SNIHostName(host)));
                s.setSSLParameters(p);
                s.startHandshake();
                System.out.println(proto + " OK -> " + s.getSession().getProtocol() + " " + s.getSession().getCipherSuite());
                s.close();
            } catch (Exception e) {
                System.out.println(proto + " FAIL -> " + e);
            }
        }
    }
}
