package uk.noammm.kav.ext

import kav.kavapp.api.Kav
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

// Raw hooks on Kav's own methods, or Android's (Kav.hook, capability "hooks"), as an Xposed module has them. Each method
// is hooked once in the engine (HookEngine), whose callback asks every extension's hooks on it in turn: their befores
// in the order they hooked, then the original unless one set a result or an error, then their afters the other way
// round. Each call into an extension's hook goes through the crash guard; one that crashes is counted and the call goes
// on without it. An extension's hooks all go when it is switched off, blocked or removed (Native.retire). In safe mode
// no extension code loads, so none hook.
//
// So that a Kav update can't break an extension through its hooks, its manifest lists the functions it hooks:
//   "hooks": [{"class": "uk.noammm.kav.ui.CommonKt", "method": "durMinutes", "params": ["int"]}]
// Kav looks each one up as the extension's code starts. One this Kav doesn't have is left out, never counted as a
// crash, and the extension's row says it needs an update for this Kav (gaps). Kav.hook by name skips it the same way,
// and Kav.hooked says whether a hook took. Kav's own release gate (android/hook/declared.txt) keeps every function the
// listed extensions hook.
object MethodHooks {
    const val CAPABILITY = "hooks"

    // A function a manifest says it hooks; method "<init>" for a constructor.
    data class Declared(val cls: String, val method: String, val params: List<String>) {
        override fun toString() = "$cls.$method(${params.joinToString(",")})"
    }

    fun declared(m: Manifest): List<Declared> = m.json.optJSONArray("hooks")?.let { a ->
        (0 until minOf(a.length(), 256)).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            val ps = o.optJSONArray("params")
            val cls = o.optString("class"); val method = o.optString("method")
            if (cls.isEmpty() || method.isEmpty()) null else Declared(cls, method, (0 until (ps?.length() ?: 0)).map { ps!!.optString(it) })
        }
    }.orEmpty()

    private val PRIMITIVES = mapOf("int" to Int::class.javaPrimitiveType, "long" to Long::class.javaPrimitiveType, "boolean" to Boolean::class.javaPrimitiveType,
        "float" to Float::class.javaPrimitiveType, "double" to Double::class.javaPrimitiveType, "byte" to Byte::class.javaPrimitiveType,
        "short" to Short::class.javaPrimitiveType, "char" to Char::class.javaPrimitiveType)

    private fun type(name: String, loader: ClassLoader): Class<*> {
        if (name.endsWith("[]")) return java.lang.reflect.Array.newInstance(type(name.dropLast(2), loader), 0).javaClass
        return PRIMITIVES[name] ?: Class.forName(name, false, loader)
    }

    // The function in this Kav, or null when it has none by that name and those parameters.
    fun find(d: Declared, loader: ClassLoader = MethodHooks::class.java.classLoader!!): Member? = runCatching {
        val c = Class.forName(d.cls, false, loader)
        val ps = d.params.map { type(it, loader) }.toTypedArray()
        if (d.method == "<init>") c.getDeclaredConstructor(*ps) else c.getDeclaredMethod(d.method, *ps)
    }.getOrNull()

    // The declared functions this Kav doesn't have, by extension: its row says it needs an update for this Kav.
    val gaps = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    // As its code starts: every declared function looked up; the missing ones noted, and written in its log.
    fun check(identity: String, m: Manifest, log: (String) -> Unit) {
        val missing = declared(m).filter { find(it) == null }.map { it.toString() }
        if (missing.isEmpty()) gaps.remove(identity) else gaps[identity] = missing
        for (x in missing) log("this Kav has no $x: its hook is left out")
    }

    // An extension's hook by name: none, and nothing thrown, when this Kav doesn't have the function or can't hook it.
    fun hook(identity: String, l: Native.Loaded, d: Declared, before: Kav.MethodHook?, after: Kav.MethodHook?, log: (String) -> Unit): Kav.Unhook {
        val m = find(d)
        if (m != null) try { return hook(identity, l, m, before, after) } catch (e: Exception) { log("couldn't hook $d: $e") }
        else log("this Kav has no $d: its hook is left out")
        gaps.merge(identity, listOf(d.toString())) { a, b -> (a + b).distinct() }
        return NONE
    }

    private val NONE = object : Kav.Unhook { override fun unhook() {} }

    // Whether this extension has a hook on the function now.
    fun hooked(identity: String, d: Declared): Boolean {
        val m = find(d) ?: return false
        return synchronized(this) { hooked[m]?.has(identity) == true }
    }

    internal class Entry(val identity: String, val before: Kav.MethodHook?, val after: Kav.MethodHook?)

    // One hooked method, the object the engine calls for it.
    class Dispatch internal constructor(private val target: Member) {
        internal lateinit var backup: Method
        private val entries = CopyOnWriteArrayList<Entry>()
        private val static = Modifier.isStatic(target.modifiers)
        private val name = "${target.declaringClass.name}.${target.name}"

        internal fun add(e: Entry) { entries += e }
        internal fun has(identity: String) = entries.any { it.identity == identity }
        internal fun remove(e: Entry): Boolean { entries -= e; return entries.isEmpty() }

        // The engine's callback for every call of the method: its receiver first for one that isn't static.
        fun callback(args: Array<Any?>): Any? {
            val self = if (static) null else args[0]
            val call = Call(this, self, if (static) args.copyOf() else args.copyOfRange(1, args.size))
            val asked = ArrayList<Entry>()
            for (e in entries) {
                e.before?.let { h -> NativeGuard.call(e.identity, "hook $name") { h.call(call) } }
                asked += e
                if (call.decided) break
            }
            if (!call.decided) call.run()
            for (e in asked.asReversed()) e.after?.let { h -> NativeGuard.call(e.identity, "hook $name") { h.call(call) } }
            call.error()?.let { throw it }
            return call.result()
        }

        internal fun original(self: Any?, args: Array<out Any?>): Any? = try {
            backup.invoke(self, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    class Call internal constructor(private val d: Dispatch, private val self: Any?, private val args: Array<Any?>) : Kav.Call {
        private var result: Any? = null
        private var error: Throwable? = null
        internal var decided = false
            private set

        override fun thisObject() = self
        override fun args() = args
        override fun result() = result
        override fun setResult(result: Any?) { this.result = result; error = null; decided = true }
        override fun error() = error
        override fun setError(error: Throwable?) { this.error = error; if (error != null) result = null; decided = true }
        override fun callOriginal(vararg args: Any?): Any? = d.original(self, args)

        internal fun run() {
            try { result = d.original(self, args) } catch (t: Throwable) { error = t }
        }
    }

    private val hooked = HashMap<Member, Dispatch>()
    private val callback: Method by lazy { Dispatch::class.java.getDeclaredMethod("callback", Array<Any?>::class.java) }

    fun hook(identity: String, l: Native.Loaded, method: Member, before: Kav.MethodHook?, after: Kav.MethodHook?): Kav.Unhook {
        require(method is Method || method is java.lang.reflect.Constructor<*>) { "only a method or a constructor can be hooked" }
        require(!Modifier.isAbstract(method.modifiers)) { "an abstract method has no code to hook" }
        if (SafeMode.active) throw IllegalStateException("no hooks in safe mode")
        if (!HookEngine.ready) throw UnsupportedOperationException("Kav's hook engine can't start on this phone")
        val e = Entry(identity, before, after)
        val d = synchronized(this) {
            val d = hooked[method] ?: Dispatch(method).also { d ->
                val backup = HookEngine.hook(method, d, callback) ?: throw IllegalArgumentException("$method can't be hooked")
                backup.isAccessible = true
                d.backup = backup
                hooked[method] = d
            }
            d.add(e)
            d
        }
        val unhook = object : Kav.Unhook {
            override fun unhook() = synchronized(this@MethodHooks) {
                if (d.remove(e) && hooked.remove(method) != null) HookEngine.unhook(method)
                l.unhooks.remove(this)
                Unit
            }
        }
        l.unhooks += unhook
        return unhook
    }

    // A method that may have inlined a small one an extension hooked: run as written again, so the hook is reached.
    fun deoptimize(method: Member): Boolean {
        if (!HookEngine.ready) throw UnsupportedOperationException("Kav's hook engine can't start on this phone")
        return HookEngine.deoptimize(method)
    }
}

// The engine itself (android/hook/kavhook.cpp, LSPlant with Dobby): its library loads the first time an extension
// hooks, never at Kav's start.
object HookEngine {
    val ready: Boolean by lazy {
        runCatching { System.loadLibrary("kavhook"); start() }.onFailure { e -> runCatching { android.util.Log.w("KavHook", "can't start: $e") } }.getOrDefault(false)
    }

    @JvmStatic external fun start(): Boolean
    @JvmStatic external fun hook(target: Member, hooker: Any, callback: Method): Method?
    @JvmStatic external fun unhook(target: Member): Boolean
    @JvmStatic external fun deoptimize(target: Member): Boolean
}
