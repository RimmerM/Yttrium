package com.rimmer.yttrium.mysql

import com.rimmer.mysql.dsl.Query
import com.rimmer.mysql.protocol.QueryResult
import com.rimmer.mysql.protocol.ResultSet
import com.rimmer.mysql.protocol.Row
import com.rimmer.yttrium.Context
import com.rimmer.yttrium.NotFoundException
import com.rimmer.yttrium.Task
import com.rimmer.yttrium.finished
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Helper functions for managing MySQL.
 */

/** Makes sure that the query contains at least one result and returns it. */
fun <T> Query.value(pool: SQLPool, context: Context, format: (Row) -> T) = map(pool, context) {
    val r = it.result
    if(r == null || r.data.isEmpty()) throw NotFoundException()
    format(r.data[0])
}

/** Fetches one result if possible, and returns it. */
fun <T> Query.maybeValue(pool: SQLPool, context: Context, format: (Row) -> T): Task<T?> {
    val future = Task<T?>()
    run(pool[context], context.listenerData) { r, e ->
        if(e == null) {
            try {
                future.finish(r?.result?.data?.elementAtOrNull(0)?.let {format(it)})
            } catch(e: Throwable) {
                future.fail(e)
            }
        } else {
            future.fail(e)
        }
    }

    return future
}

/** Fetches one result if possible, and runs the provided task otherwise. */
fun <T> Query.valueOrElse(pool: SQLPool, context: Context, format: (Row) -> T, otherwise: () -> Task<T>): Task<T> {
    val future = Task<T>()
    run(pool[context], context.listenerData) { r, e ->
        if(e == null) {
            val result = r!!.result
            if(result == null || result.data.isEmpty()) {
                val task = otherwise()
                task.handler = { r, e ->
                    if(e == null) {
                        future.finish(r!!)
                    } else {
                        future.fail(e)
                    }
                }
            } else {
                try {
                    future.finish(format(result.data[0]))
                } catch(e: Throwable) {
                    future.fail(e)
                }
            }
        } else {
            future.fail(e)
        }
    }

    return future
}

/** Makes sure that the query contains at least one result and returns the single column. */
fun <T> Query.value(pool: SQLPool, context: Context) = value(pool, context) { it[0] as T }

/** Fetches one result if possible, and returns the single column. */
fun <T> Query.maybeValue(pool: SQLPool, context: Context) = maybeValue(pool, context) { it[0] as T }

/** Fetches one result if possible and returns the single column. Otherwise, the provided task is run. */
fun <T> Query.valueOrElse(pool: SQLPool, context: Context, otherwise: () -> Task<T>) =
    valueOrElse(pool, context, { it[0] as T }, otherwise)

/** Fetches a list of results and formats each entry. */
fun <T> Query.values(pool: SQLPool, context: Context, format: (Row) -> T) = map(pool, context) {
    val list = ArrayList<T>()
    it.result?.data?.forEach { list.add(format(it)) }
    list
}

/** Fetches a list of results and formats each entry. */
fun <T> Query.values(pool: SQLPool, context: Context) = values(pool, context) {it[0] as T}

/** Creates an associative map from a result query. */
fun <K, V> Query.asMap(pool: SQLPool, context: Context, key: (Row) -> K, value: (Row) -> V) = map(pool, context) {
    val map = HashMap<K, V>()
    it.result?.data?.forEach {
        map[key(it)] = value(it)
    }
    map
}

/** Performs an action if the query succeeded. */
fun <T> Query.map(pool: SQLPool, context: Context, f: (QueryResult) -> T): Task<T> {
    val task = Task<T>()
    run(pool[context], context.listenerData) {r, e ->
        if(e == null) {
            try {
                task.finish(f(r!!))
            } catch(e: Throwable) {
                task.fail(e)
            }
        } else {
            task.fail(e)
        }
    }
    return task
}

/** Performs a task if the query succeeded. */
fun <T> Query.then(pool: SQLPool, context: Context, f: (QueryResult) -> Task<T>): Task<T> {
    val task = Task<T>()
    run(pool[context], context.listenerData) {r, e ->
        if(e == null) {
            try {
                val next = f(r!!)
                next.handler = {r, e ->
                    if (e == null) {
                        task.finish(r!!)
                    } else {
                        task.fail(e)
                    }
                }
            } catch(e: Throwable) {
                task.fail(e)
            }
        } else {
            task.fail(e)
        }
    }
    return task
}

/** Executes this query as a task. */
fun Query.task(pool: SQLPool, context: Context): Task<QueryResult> {
    val task = Task<QueryResult>()
    run(pool[context], context.listenerData) {r, e ->
        if(e == null) {
            task.finish(r!!)
        } else {
            task.fail(e)
        }
    }
    return task
}

/** Executes this query as a task. */
fun Query.streamingTask(pool: SQLPool, context: Context, chunkSize: Int = 1000, onResult: (ResultSet) -> Unit): Task<QueryResult> {
    val task = Task<QueryResult>()
    run(pool[context], context.listenerData, chunkSize, onResult) {r, e ->
        if(e == null) {
            task.finish(r!!)
        } else {
            task.fail(e)
        }
    }
    return task
}

/** Executes this query as a task without returning anything. */
fun Query.unitTask(pool: SQLPool, context: Context): Task<Unit> {
    val task = Task<Unit>()
    run(pool[context], context.listenerData) {r, e ->
        if(e == null) {
            task.finish(Unit)
        } else {
            task.fail(e)
        }
    }
    return task
}

/** Performs the provided actions in parallel and finishes when all have been executed. */
fun parallel(pool: SQLPool, context: Context, vararg queries: Query): Task<Unit> {
    val total = queries.size
    if(total == 0) {
        return finished(Unit)
    }

    val count = AtomicInteger(0)
    val failed = AtomicBoolean(false)
    val task = Task<Unit>()

    queries.forEach {
        it.run(pool[context], context.listenerData) { r, e ->
            if(e == null) {
                val finished = count.incrementAndGet()
                if(finished >= total) {
                    task.finish(Unit)
                }
            } else {
                val wasFailed = failed.getAndSet(true)
                if(!wasFailed) {
                    task.fail(e)
                }
            }
        }
    }
    return task
}