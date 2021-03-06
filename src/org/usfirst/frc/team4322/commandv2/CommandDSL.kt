package org.usfirst.frc.team4322.commandv2

import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async

interface Element {
    operator fun invoke(): Deferred<Unit>
}

class Group : CommandSet()

@DslMarker
annotation class CommandMarker

enum class Location {
    Children,
    Commands
}

@CommandMarker
abstract class CommandSet : Element {
    protected val children = arrayListOf<Element>()
    protected val order = arrayListOf<Pair<Location, Int>>()
    /**
     * Creates a block of commands that run in parallel. This block will run until all it's members terminate.
     */
    fun parallel(init: Parallel.() -> Unit) = initTag(Parallel(), init)

    /**
     * Creates a block of commands that run in sequential order. This block will run until all it's members terminate.
     */
    fun sequential(init: Sequential.() -> Unit) = initTag(Sequential(), init)

    private fun <T : Element> initTag(tag: T, init: T.() -> Unit): T {
        tag.init()
        children.add(tag)
        order.add(Pair(Location.Children, children.size - 1))
        return tag
    }

    override operator fun invoke(): Deferred<Unit> {
        return async(start = CoroutineStart.LAZY) {
            for (child in children) {
                child().await()
            }
        }
    }
}

abstract class SubSet : CommandSet() {
    val commands = arrayListOf<Any>()
    operator fun Command.unaryPlus() {
        add(this)
    }

    fun add(op: Any) {
        commands.add(this)
        order.add(Pair(Location.Commands, commands.size - 1))
    }

    operator fun Router.unaryPlus() {
        add(this)
    }

    operator fun CommandSet.unaryPlus() {
        this@SubSet.add(this)
    }
}

class Parallel : SubSet() {
    override operator fun invoke(): Deferred<Unit> {
        return async {
            val tasks = mutableListOf<Deferred<Unit>>()
            for (entry in order) {
                when (entry.first) {
                    Location.Children -> {
                        tasks.add(async { children[entry.second]().await() })
                    }
                    Location.Commands -> {
                        val command = commands[entry.second]
                        when (command) {
                            is Router -> tasks.add(async { command.route()().await() })
                            is Command -> tasks.add(async { command().await() })
                        }
                    }
                }
            }
            tasks.forEach { it.await() }
        }
    }
}


class Sequential : SubSet() {
    override operator fun invoke(): Deferred<Unit> {
        return async {
            for (entry in order) {
                when (entry.first) {
                    Location.Children -> {
                        children[entry.second]().await()
                    }
                    Location.Commands -> {
                        val command = commands[entry.second]
                        when (command) {
                            is Router -> command.route()().await()
                            is Command -> command().await()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Starts a CommandSet DSL. Inside the DSL, commands may be placed in [CommandSet.sequential] and [CommandSet.parallel] blocks.
 * Commands are added to blocks via putting a plus symbol ahead of their declaration.
 */
fun group(init: Group.() -> Unit): Group {
    val set = Group()
    set.init()
    return set
}

/* Example:

val foo = group {
    parallel {
        +CommandBuilder.create().build()
        +CommandBuilder.create().build()
        sequential {
            +CommandBuilder.create().build()
            +CommandBuilder.create().build()
        }
    }
    sequential {
        +CommandBuilder.create().build()
        +CommandBuilder.create().build()
    }
}
*/
/*
remember to call .synthesize() on the group to turn the DSL into CommandGroup instances.
*/