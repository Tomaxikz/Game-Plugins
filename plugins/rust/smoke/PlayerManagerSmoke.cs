// PlayerManagerSmoke - one-time Unix socket smoke test for Oxide and Carbon (rust.md section 10).
// Not part of the release. Install as oxide/plugins/PlayerManagerSmoke.cs or carbon/plugins/PlayerManagerSmoke.cs.
// Never install together with PlayerManagerRust in the same run (different socket name, but keep results clean).
using System;
using System.IO;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using Oxide.Core;

namespace Oxide.Plugins
{
    [Info("PlayerManagerSmoke", "PlayerManager", "1.0.0")]
    [Description("Smoke test: UnixDomainSocketEndPoint, NextTick drain and wear container capacity.")]
    public class PlayerManagerSmoke : RustPlugin
    {
        private const string SockRel = ".playermanager/smoke.sock";
        private Socket listener;
        private System.Threading.Thread acceptThread;
        private string boundPath;
        private volatile bool stopping;

        private void Log(string s) { Puts("{0}", "[smoke] " + s); }

        private void OnServerInitialized()
        {
#if CARBON
            Log("framework: Carbon");
#else
            Log("framework: Oxide");
#endif
            try { Log("Application/Rust protocol: " + Rust.Protocol.printable); } catch (Exception e) { Log("protocol read failed: " + e.Message); }

            // Step 1: environment facts.
            try
            {
                Log("UnixDomainSocketEndPoint assembly = " + typeof(UnixDomainSocketEndPoint).Assembly.FullName);
            }
            catch (Exception e) { Log("UnixDomainSocketEndPoint type lookup FAILED: " + e); }
            try
            {
                // Static property exists only on newer runtimes; read by reflection so this file always compiles.
                var p = typeof(Socket).GetProperty("OSSupportsUnixDomainSockets", BindingFlags.Public | BindingFlags.Static);
                Log("Socket.OSSupportsUnixDomainSockets = " + (p == null ? "(property not present in this runtime)" : Convert.ToString(p.GetValue(null, null))));
            }
            catch (Exception e) { Log("OSSupportsUnixDomainSockets read failed: " + e.Message); }
            Log("Socket assembly = " + typeof(Socket).Assembly.FullName);

            // Step: NextTick drain.
            var t0 = DateTime.UtcNow;
            NextTick(() => Log("NextTick drain FIRED after " + (DateTime.UtcNow - t0).TotalMilliseconds.ToString("0") + " ms"));
            timer.Once(1f, () => Log("timer.Once(1s) fired (timers work)"));

            // Step 2: bind and accept on a worker thread. Never block the game thread.
            var th = new System.Threading.Thread(StartSocket) { IsBackground = true, Name = "pm-smoke-accept" };
            th.Start();
        }

        private void StartSocket()
        {
            try
            {
                Directory.CreateDirectory(".playermanager");
                string abs = Path.GetFullPath(SockRel);
                string path = Encoding.UTF8.GetByteCount(abs) < 108 ? abs : SockRel;
                if (Encoding.UTF8.GetByteCount(path) >= 108) { Log("socket path >= 108 bytes: " + path); return; }
                try { if (File.Exists(path)) File.Delete(path); } catch { }
                listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
                listener.Bind(new UnixDomainSocketEndPoint(path));
                listener.Listen(4);
                boundPath = path;
                Log("bound + listening on " + path + " (" + Encoding.UTF8.GetByteCount(path) + " bytes)");
                acceptThread = System.Threading.Thread.CurrentThread;
                while (!stopping)
                {
                    Socket c;
                    try { c = listener.Accept(); }
                    catch (Exception) { if (stopping) break; throw; }
                    var ct = new System.Threading.Thread(() => Echo(c)) { IsBackground = true, Name = "pm-smoke-conn" };
                    ct.Start();
                }
            }
            catch (Exception e) { Log("SOCKET SETUP FAILED -> ship TCP fallback: " + e); }
        }

        private void Echo(Socket c)
        {
            try
            {
                Log("client connected");
                var ns = new NetworkStream(c, true);
                var hdr = new byte[4];
                while (true)
                {
                    if (!ReadExact(ns, hdr, 4)) break;
                    int len = (hdr[0] << 24) | (hdr[1] << 16) | (hdr[2] << 8) | hdr[3];
                    if (len < 0 || len > 1 << 20) { Log("bad length " + len); break; }
                    var body = new byte[len];
                    if (!ReadExact(ns, body, len)) break;
                    var outb = new byte[4 + len];
                    Buffer.BlockCopy(hdr, 0, outb, 0, 4);
                    Buffer.BlockCopy(body, 0, outb, 4, len);
                    ns.Write(outb, 0, outb.Length);
                    ns.Flush();
                    Log("echoed " + len + " bytes");
                }
                Log("client closed");
            }
            catch (Exception e) { Log("echo error: " + e.Message); }
            finally { try { c.Close(); } catch { } }
        }

        private static bool ReadExact(Stream s, byte[] buf, int n)
        {
            int got = 0;
            while (got < n)
            {
                int r = s.Read(buf, got, n - got);
                if (r <= 0) return false;
                got += r;
            }
            return true;
        }

        // Step: wear container capacity (spec says 8).
        private void OnPlayerConnected(BasePlayer player)
        {
            try
            {
                var inv = player.inventory;
                Log("wear capacity = " + inv.containerWear.capacity + ", main = " + inv.containerMain.capacity + ", belt = " + inv.containerBelt.capacity + " (player " + player.UserIDString + ")");
            }
            catch (Exception e) { Log("capacity read failed: " + e.Message); }
        }

        private void Unload()
        {
            stopping = true;
            try { if (listener != null) listener.Close(); } catch { }
            try { if (boundPath != null && File.Exists(boundPath)) { File.Delete(boundPath); Log("socket file deleted on unload"); } } catch (Exception e) { Log("delete failed: " + e.Message); }
        }
    }
}
