// Hand-written stubs of the Oxide/Carbon and Rust members PlayerManagerRust.cs uses.
// Purpose: compile-check the single plugin file in CI without the game. Every member here is an ASSUMPTION about
// the real API (see NOTES-section.md, "verify on smoke test"); members confirmed against Oxide.Rust 2.0.7826's
// Assembly-CSharp.dll metadata are marked [verified: Assembly-CSharp], those seen referenced by Oxide/Carbon
// binaries [verified: Oxide/Carbon refs], the rest [unverified].
#pragma warning disable 0649, 0169, 0414, 0067, 0219
using System;
using System.Collections.Generic;

namespace UnityEngine
{
    public struct Vector3 { public float x, y, z; public Vector3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; } }
    public class Object { public string name; }
    public class Component : Object { public Transform transform = new Transform(); }
    public class Transform { public Vector3 position; }
    public static class Mathf { public static float Clamp(float v, float a, float b) { return v < a ? a : (v > b ? b : v); } }
}

namespace Oxide.Core
{
    public struct VersionNumber { public int Major, Minor, Patch; public override string ToString() { return Major + "." + Minor + "." + Patch; } }
    public class OxideMod
    {
        public static VersionNumber Version;                                  // [verified: Oxide.Core]
        public string DataDirectory = ".";                                    // [verified: Oxide.Core, Carbon.Common]
    }
    public static class Interface { public static OxideMod Oxide = new OxideMod(); }   // [verified]
}

namespace Oxide.Core.Libraries
{
    public class Permission                                                   // [verified: Oxide.Core]
    {
        public string[] GetUserGroups(string id) { return new string[0]; }
        public bool UserHasGroup(string id, string group) { return false; }
        public string[] GetUsersInGroup(string group) { return new string[0]; }
    }
}

namespace Oxide.Core.Plugins
{
    public class Plugin
    {
        public string Name, Title;
        public VersionNumber Version;
        public bool IsCorePlugin;
        public PluginManager Manager { get; set; }                            // [verified: Oxide.Core]
        public object Call(string hook, params object[] args) { return null; }   // [verified]
        protected void Subscribe(string hook) { }                             // [assumed protected, documented API]
        protected void Unsubscribe(string hook) { }
    }
    public class PluginManager { public IEnumerable<Plugin> GetPlugins() { return new Plugin[0]; } }   // [verified]
}

namespace Oxide.Plugins
{
    [AttributeUsage(AttributeTargets.Class)] public class InfoAttribute : Attribute { public InfoAttribute(string t, string a, string v) { } }          // [verified]
    [AttributeUsage(AttributeTargets.Class)] public class DescriptionAttribute : Attribute { public DescriptionAttribute(string d) { } }              // [verified]
    [AttributeUsage(AttributeTargets.Field)] public class PluginReferenceAttribute : Attribute { public PluginReferenceAttribute() { } }              // [verified]
    [AttributeUsage(AttributeTargets.Method)] public class ConsoleCommandAttribute : Attribute { public ConsoleCommandAttribute(string c) { } }      // [verified]
    public class Timer { public void Destroy() { } }                          // [verified]
    public class PluginTimers                                                 // [verified]
    {
        public Timer Every(float s, Action a) { return new Timer(); }
        public Timer Once(float s, Action a) { return new Timer(); }
    }
    public class RustPlugin : Oxide.Core.Plugins.Plugin
    {
        protected Oxide.Core.Libraries.Permission permission = new Oxide.Core.Libraries.Permission();   // [verified]
        protected PluginTimers timer = new PluginTimers();                                              // [verified]
        protected void Puts(string format, params object[] args) { }                                    // [verified]
        protected void PrintError(string format, params object[] args) { }
        protected void PrintWarning(string format, params object[] args) { }
        protected void NextTick(Action a) { }                                                           // [verified]
    }
}

namespace Rust { public static class Protocol { public static string printable = "0"; } }   // [verified: Oxide/Carbon refs get_printable]

namespace Network
{
    public class Connection { public string ipaddress; public uint authLevel; }          // ipaddress [verified], authLevel [verified]
    public class Networkable { public ItemIdLike ID; public Connection connection; }       // [assumed shapes]
    public struct ItemIdLike { public ulong Value; public override string ToString() { return Value.ToString(); } }
    public class Server { public int GetAveragePing(Connection c) { return 0; } }          // [verified: Facepunch.Network]
    public static class Net { public static Server sv = new Server(); }                    // [verified]
}

namespace ConVar
{
    public static class Server                                                  // [verified for hostname, description, level, headerimage, maxplayers; old plugin used worldsize, seed, url]
    {
        public static string hostname = "", description = "", level = "Procedural Map", headerimage = "", url = "";
        public static int maxplayers = 100, worldsize = 4500, seed = 1234567;
        public static int saveinterval = 600;                                   // [unverified]
    }
    public static class Chat { public enum ChatChannel { Global, Team, Server, Cards, Local, Clan } }    // [unverified member names]
}

public class ConsoleSystem
{
    public class Command { public string FullName = ""; }
    public class Arg
    {
        public Network.Connection Connection;                                   // [verified: Facepunch.Console]
        public string[] Args;                                                   // [verified: Carbon refs]
        public Command cmd = new Command();                                     // [verified]
        public void ReplyWith(string s) { }                                     // [verified]
    }
}

public static class Performance { public class Tick { public float frameRate; } public static Tick report = new Tick(); }    // [verified: Carbon refs report, frameRate]

public enum DamageType { Generic, Bullet, Blunt, Slash }
public class DamageTypeList { public float Total() { return 0; } public DamageType GetMajorityDamageType() { return DamageType.Generic; } }   // [unverified]
public class HitInfo { public DamageTypeList damageTypes = new DamageTypeList(); public BasePlayer InitiatorPlayer; public BaseEntity Initiator; }    // [verified: Assembly-CSharp]

public class ListHashSet<T> : List<T> { }

public struct EncryptedValue<T> { public T v; public static implicit operator T(EncryptedValue<T> e) { return e.v; } public static implicit operator EncryptedValue<T>(T t) { return new EncryptedValue<T> { v = t }; } }   // userID type [verified]

public class BaseNetworkable : UnityEngine.Component
{
    public Network.Networkable net = new Network.Networkable();                 // [verified]
    public string ShortPrefabName { get { return ""; } }                         // [verified]
    public class EntityRealm { public int Count { get { return 0; } } }          // [verified: Carbon refs]
    public static EntityRealm serverEntities = new EntityRealm();
}
public class BaseEntity : BaseNetworkable { public void SendNetworkUpdateImmediate() { } }   // [verified]
public class BaseCombatEntity : BaseEntity                                       // [verified: Assembly-CSharp]
{
    public float health { get; set; }
    public float MaxHealth() { return 100; }
    public bool IsDead() { return false; }
    public bool IsAlive() { return true; }
    public void Heal(float f) { }
    public void Hurt(float f) { }
    public void SetHealth(float f) { }
    public void Die(HitInfo info = null) { }
}
public class BaseMountable : BaseEntity { }
public class BaseProjectile : BaseEntity { public class Magazine { public int contents; public ItemDefinition ammoType; } public Magazine primaryMagazine; }    // [verified]

public class MetabolismAttribute { public float value; public void SetValue(float v) { } }       // [verified]
public class BaseMetabolism { public MetabolismAttribute calories, hydration; }                    // calories/hydration/heartrate on BaseMetabolism<T>: [verified by old plugin for calories+hydration]
public class PlayerMetabolism : BaseMetabolism                                                     // [verified: Assembly-CSharp]
{
    public MetabolismAttribute temperature, poison, radiation_level, radiation_poison, wetness, oxygen, bleeding, comfort;
}
public class PlayerLifeStory { public float secondsAlive; }                                        // [verified by old plugin]
public class PlayerBlueprints { public bool HasUnlocked(ItemDefinition d) { return false; } }      // [verified]

public class ItemDefinition { public string shortname; public int itemid; public int stackable = 1; }   // shortname,itemid [verified: Carbon refs]; stackable [unverified]
public struct ItemId { public ulong Value; public override string ToString() { return Value.ToString(); } }
public class Item
{
    public ItemDefinition info;                                                  // [verified]
    public ItemId uid;                                                           // [verified name; type varies by build]
    public int amount, position;                                                 // [verified]
    public ulong skin;                                                           // [verified]
    public string name;                                                          // [verified]
    public float condition, maxCondition;                                        // [verified]
    public bool hasCondition;                                                    // [verified]
    public ItemContainer contents;                                               // [verified]
    public ItemDefinition blueprintTargetDef;                                    // [verified]
    public bool IsBlueprint() { return false; }                                  // [verified]
    public BaseEntity GetHeldEntity() { return null; }                           // [verified]
}
public class ItemContainer { public int capacity; public List<Item> itemList = new List<Item>(); }   // [verified]
public class PlayerInventory { public ItemContainer containerMain, containerBelt, containerWear; public void Strip() { } }    // [verified]
public static class ItemManager                                                  // [verified: Carbon refs CreateByName, FindItemDefinition]
{
    public static Item CreateByName(string n, int amount = 1, ulong skin = 0) { return null; }
    public static ItemDefinition FindItemDefinition(string n) { return null; }
    public static List<ItemDefinition> GetItemDefinitions() { return new List<ItemDefinition>(); }    // old plugin used it
}

public static class ServerUsers                                                  // [verified: Oxide/Carbon refs Is, Remove, Save, Set; GetAll + User fields via old plugin]
{
    public enum UserGroup { None, Owner, Moderator, Banned }
    public class User { public ulong steamid; public string username, notes; }
    public static IEnumerable<User> GetAll(UserGroup g) { return new User[0]; }
    public static bool Is(ulong id, UserGroup g) { return false; }
    public static void Set(ulong id, UserGroup g, string name, string notes, long expiry = -1) { }
    public static void Remove(ulong id) { }
    public static void Save() { }
}
public static class SaveRestore { public static DateTime SaveCreatedTime; public static bool Save(bool andWait) { return true; } }   // [verified: Assembly-CSharp]
public class MonumentInfo : UnityEngine.Component { }                            // [unverified members]
public class TerrainPath { public List<MonumentInfo> Monuments = new List<MonumentInfo>(); }   // [verified: field Monuments]
public static class TerrainMeta { public static TerrainPath Path = new TerrainPath(); }          // [verified: Carbon refs get_Path]

public class RelationshipManager                                                 // [verified: Assembly-CSharp]
{
    public class PlayerTeam { public ulong teamID, teamLeader; public List<ulong> members = new List<ulong>(); }
    public static RelationshipManager ServerInstance { get; set; }
    public PlayerTeam FindTeam(ulong id) { return null; }
}

public class BasePlayer : BaseCombatEntity
{
    [Flags] public enum PlayerFlags { Unused = 1, IsAdmin = 4, ChatMute = 4096 }          // [verified: IsAdmin, ChatMute exist]
    public static ListHashSet<BasePlayer> activePlayerList = new ListHashSet<BasePlayer>();      // [verified: old plugin]
    public static ListHashSet<BasePlayer> sleepingPlayerList = new ListHashSet<BasePlayer>();
    public EncryptedValue<ulong> userID;                                                          // [verified]
    public string UserIDString;                                                                   // [verified]
    public string displayName { get; set; }                                                       // [verified]
    public PlayerInventory inventory;                                                             // [verified]
    public PlayerMetabolism metabolism;                                                           // [verified]
    public PlayerBlueprints blueprints;                                                           // [verified]
    public PlayerLifeStory lifeStory;                                                             // [verified by old plugin]
    public ulong currentTeam;                                                                     // [verified]
    public long clanId;                                                                           // [verified]
    public object IPlayer;                                                                        // [verified field name; type is the framework's IPlayer]
    public bool IsAdmin { get; set; }                                                             // [verified]
    public bool IsConnected { get; set; }                                                         // [verified]
    public int secondsConnected { get; set; }                                                     // [verified]
    public static BasePlayer FindByID(ulong id) { return null; }                                  // [verified]
    public static BasePlayer FindSleeping(ulong id) { return null; }                              // [verified]
    public static BasePlayer FindAwakeOrSleepingByID(ulong id) { return null; }                   // [verified]
    public bool IsSleeping() { return false; }
    public bool IsWounded() { return false; }
    public bool IsCrawling() { return false; }
    public bool IsSpectating() { return false; }
    public bool IsBuildingAuthed() { return false; }
    public Item GetActiveItem() { return null; }
    public BaseMountable GetMounted() { return null; }
    public void Kick(string reason, bool reserveSlot = false) { }
    public void GiveItem(Item i) { }                                                              // real: (Item, GiveItemReason = default, GiveItemOptions = default) [verified defaults]
    public void SetPlayerFlag(PlayerFlags f, bool b) { }
}
