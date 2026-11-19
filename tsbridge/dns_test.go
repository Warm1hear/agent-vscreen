package main

import (
	"context"
	"net"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/net/dnscache"
)

// A dead first resolver (the VPN's DNS when the app is bypassed) must not stop the lookup: the next one answers.
func TestFanoutSkipsSilentServer(t *testing.T) {
	silent, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer silent.Close() // reads nothing, answers nothing

	good, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer good.Close()
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := good.ReadFrom(buf)
			if err != nil {
				return
			}
			var p dnsmessage.Parser
			h, err := p.Start(buf[:n])
			if err != nil {
				continue
			}
			q, err := p.Question()
			if err != nil {
				continue
			}
			b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: h.ID, Response: true, Authoritative: true})
			b.EnableCompression()
			_ = b.StartQuestions()
			_ = b.Question(q)
			_ = b.StartAnswers()
			if q.Type == dnsmessage.TypeA {
				_ = b.AResource(dnsmessage.ResourceHeader{Name: q.Name, Class: dnsmessage.ClassINET, TTL: 60}, dnsmessage.AResource{A: [4]byte{10, 1, 2, 3}})
			}
			out, _ := b.Finish()
			_, _ = good.WriteTo(out, from)
		}
	}()

	servers := []string{silent.LocalAddr().String(), good.LocalAddr().String()}
	oldDefault, oldForward := net.DefaultResolver, dnscache.Get().Forward
	t.Cleanup(func() { net.DefaultResolver, dnscache.Get().Forward = oldDefault, oldForward })
	setupDNS(servers)
	if dnscache.Get().Forward != net.DefaultResolver {
		t.Fatal("control client and bridge must use the configured Android resolver")
	}
	r := &dnscache.Resolver{Forward: dnscache.Get().Forward}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	start := time.Now()
	ip, _, _, err := r.LookupIP(ctx, "headscale.example.test")
	if err != nil {
		t.Fatalf("lookup failed: %v", err)
	}
	if ip.String() != "10.1.2.3" {
		t.Fatalf("got %v", ip)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("too slow (%v): the silent server was waited for", time.Since(start))
	}
}

func TestPhoneDNSIsNotMixedWithPublicDefaults(t *testing.T) {
	got := dnsServerList(" 172.19.0.2 ,[fe80::1],172.19.0.2,,223.5.5.5")
	want := []string{"172.19.0.2:53", "[fe80::1]:53", "223.5.5.5:53"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v", got)
		}
	}
}

func TestEmptyAndroidDNS(t *testing.T) {
	if got := dnsServerList("invalid-name"); len(got) != 0 {
		t.Fatalf("phone list received public defaults %v", got)
	}
}

// answeringServer replies to A queries with ip.
func answeringServer(t *testing.T, ip [4]byte) string {
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			var m dnsmessage.Message
			if m.Unpack(buf[:n]) != nil {
				continue
			}
			m.Response = true
			if m.Questions[0].Type == dnsmessage.TypeA {
				m.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: ip}}}
			}
			if reply, err := m.Pack(); err == nil {
				_, _ = pc.WriteTo(reply, addr)
			}
		}
	}()
	return pc.LocalAddr().String()
}

func silentServer(t *testing.T) string {
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	return pc.LocalAddr().String()
}

// Every phone resolver dead: the public ones are asked after lateAfter, and the lookup still succeeds.
func TestPublicFallbackWhenPhoneDNSIsDead(t *testing.T) {
	late := answeringServer(t, [4]byte{10, 9, 9, 9})
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	start := time.Now()
	got, err := newResolver([]string{silentServer(t), silentServer(t)}, []string{late}).LookupIP(ctx, "ip4", "hs.example.test.")
	if err != nil || len(got) != 1 || got[0].String() != "10.9.9.9" {
		t.Fatalf("got %v, %v", got, err)
	}
	if d := time.Since(start); d < lateAfter-100*time.Millisecond {
		t.Fatalf("public resolver asked too early (%v)", d)
	}
}

// A phone resolver that answers keeps the public ones out, even if they would answer differently.
func TestPublicNotAskedWhenPhoneAnswers(t *testing.T) {
	phone := answeringServer(t, [4]byte{10, 1, 1, 1})
	late := answeringServer(t, [4]byte{8, 8, 8, 8})
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	got, err := newResolver([]string{phone}, []string{late}).LookupIP(ctx, "ip4", "hs.example.test.")
	if err != nil || len(got) != 1 || got[0].String() != "10.1.1.1" {
		t.Fatalf("got %v, %v", got, err)
	}
}

// Android supplied no resolver: the public ones are used at once.
func TestNoPhoneDNSUsesPublicAtOnce(t *testing.T) {
	late := answeringServer(t, [4]byte{10, 7, 7, 7})
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	start := time.Now()
	got, err := newResolver(nil, []string{late}).LookupIP(ctx, "ip4", "hs.example.test.")
	if err != nil || len(got) != 1 || got[0].String() != "10.7.7.7" {
		t.Fatalf("got %v, %v", got, err)
	}
	if d := time.Since(start); d > time.Second {
		t.Fatalf("too slow (%v)", d)
	}
}

// Two phone networks may have different DNS views of the same private name.
func TestFastNegativeDoesNotHidePrivateAnswer(t *testing.T) {
	var servers []string
	for _, positive := range []bool{false, true} {
		pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer pc.Close()
		servers = append(servers, pc.LocalAddr().String())
		go func() {
			buf := make([]byte, 1500)
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			var m dnsmessage.Message
			if m.Unpack(buf[:n]) != nil {
				return
			}
			m.Response = true
			if positive {
				time.Sleep(40 * time.Millisecond)
				m.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: [4]byte{10, 2, 3, 4}}}}
			} else {
				m.RCode = dnsmessage.RCodeNameError
			}
			reply, err := m.Pack()
			if err == nil {
				_, _ = pc.WriteTo(reply, addr)
			}
		}()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	got, err := newResolver(servers, nil).LookupIP(ctx, "ip4", "private.example.test.")
	if err != nil || len(got) != 1 || got[0].String() != "10.2.3.4" {
		t.Fatalf("got %v, %v", got, err)
	}
}

// After an app update the old bridge's exe link points into the previous APK directory, maybe marked deleted.
func TestExeNameSurvivesAppUpdate(t *testing.T) {
	cur := "/data/app/~~new==/com.kilon.agentvscreen-B==/lib/arm64/libtsbridge.so"
	old := "/data/app/~~old==/com.kilon.agentvscreen-A==/lib/arm64/libtsbridge.so (deleted)"
	if exeName(old) != exeName(cur) || exeName("/system/bin/sh") == exeName(cur) {
		t.Fatal("bridge binaries must match by name only")
	}
}
