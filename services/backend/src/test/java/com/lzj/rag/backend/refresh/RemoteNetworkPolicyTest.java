package com.lzj.rag.backend.refresh;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.assertj.core.api.Assertions.*;

class RemoteNetworkPolicyTest {
    @Test void defaultAddressPolicyRejectsPrivateSpecialAndTransitionAddresses()throws Exception {
        for(String ip:new String[]{"0.0.0.0","10.1.2.3","127.0.0.1","100.64.1.1","169.254.169.254","172.16.1.2","192.168.1.2","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1","168.63.129.16","::","::1","fd00::1","fe80::1","2001:db8::1","2002:7f00:1::1"})
            assertThat(RemoteDownloader.permitted(InetAddress.getByName(ip),false)).as(ip).isFalse();
        for(String ip:new String[]{"1.1.1.1","8.8.8.8","2606:4700:4700::1111"})assertThat(RemoteDownloader.permitted(InetAddress.getByName(ip),false)).as(ip).isTrue();
        assertThat(RemoteDownloader.permitted(InetAddress.getByName("127.0.0.1"),true)).isTrue();
        assertThat(RemoteDownloader.permitted(InetAddress.getByName("169.254.169.254"),true)).isFalse();
    }
}
