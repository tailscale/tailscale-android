import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import com.tailscale.ipn.DnsConfig;

public class DnsConfigTest {
	DnsConfig dns;

	@Before
	public void setup() {
		dns = new DnsConfig(null);
	}

	@Test
	public void dnsConfig_intToInetStringTest() {
		assertEquals(dns.intToInetString(0x0101a8c0), "192.168.1.1");
		assertEquals(dns.intToInetString(0x04030201), "1.2.3.4");
		assertEquals(dns.intToInetString(0), "0.0.0.0");
	}
}
