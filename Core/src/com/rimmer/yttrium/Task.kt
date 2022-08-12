package com.rimmer.yttrium

enum class TaskState {waiting, finished, error}

inline fun <T> task(f: Task<T>.() -> Unit): Task<T> {
    val t = Task<T>()
    t.f()
    return t
}

fun <T> finished(v: T): Task<T> {
    val task = Task<T>()
    task.finish(v)
    return task
}

fun <T> failed(reason: Throwable): Task<T> {
    val task = Task<T>()
    task.fail(reason)
    return task
}

/** Represents a task that will be performed asynchronously and will either provide a result or fail. */
class Task<T> {
    /**
     * Indicates that the task should finish with the provided value.
     * @return This task.
     */
    fun finish(v: T): Task<T> {
        if(state == TaskState.waiting) {
            state = TaskState.finished
            cachedResult = v
            handler?.invoke(v, null)
        } else {
            throw IllegalStateException("This task already has a result")
        }
        return this
    }

    /**
     * Indicates that the task should fail with the provided reason.
     * @return This task.
     */
    fun fail(reason: Throwable): Task<T> {
        if(state == TaskState.waiting) {
            state = TaskState.error
            cachedError = reason
            handler?.invoke(null, reason)
        } else {
            throw IllegalStateException("This task already has a result")
        }
        return this
    }

    /** Call the provided handler when finished. */
    fun onFinish(f: (T) -> Unit) {
        handler = {r, _ ->
            try {
                r?.let(f)
            } catch(e: Throwable) {
                println("Error in task onFinish handler: $e")
            }
        }
    }

    /** Call the provided handler when failed. */
    fun onFail(f: (Throwable) -> Unit) {
        handler = {_, e ->
            try {
                e?.let(f)
            } catch(e: Throwable) {
                println("Error in task onFail handler: $e")
            }
        }
    }

    /** Maps the task through the provided function, returning a new task. */
    fun <U> map(f: (T) -> U): Task<U> {
        val task = Task<U>()
        handler = {r, e ->
            if(e == null) {
                try {
                    // This cast is needed to satisfy the type system without being able to specialize generics:
                    // If `T` is a nullable type, `r` is either null or a value.
                    // If `T` is not nullable type, `r` is a value.
                    // From the compiler's perspective it could be null in the second case,
                    // but we know that the other functions in the class won't allow sending in null here.
                    // By using this specific cast, the compiler won't insert any explicit null checks
                    // and so this will work correctly in both the above cases.
                    task.finish(f(r as T))
                } catch(e: Throwable) {
                    task.fail(e)
                }
            } else {
                task.fail(e)
            }
        }
        return task
    }

    /** Maps the task through the provided functions, returning a new task. */
    fun <U> map(succeed: (T) -> U, fail: (Throwable) -> U): Task<U> {
        val task = Task<U>()
        handler = {r, e ->
            if(e == null) {
                try {
                    // See `map(f)` for an explanation of this cast.
                    task.finish(succeed(r as T))
                } catch(e: Throwable) {
                    task.fail(e)
                }
            } else {
                try {
                    task.finish(fail(e))
                } catch(e: Throwable) {
                    task.fail(e)
                }
            }
        }
        return task
    }

    /** Maps the task failure through the provided function, returning a new task. */
    fun catch(f: (Throwable) -> T) = map({it}, {f(it)})

    /** Runs the provided task generator on finish, returning the new task. */
    fun <U> then(f: (T) -> Task<U>): Task<U> {
        val task = Task<U>()
        handler = {r, e ->
            if(e == null) {
                try {
                    // See `map(f)` for an explanation of this cast.
                    val next = f(r as T)
                    next.handler = {r, e ->
                        if(e == null) {
                            task.finish(r as U)
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

    /** Runs the provided task generator on finish or failure, returning the new task. */
    fun <U> then(succeed: (T) -> Task<U>, fail: (Throwable) -> Task<U>): Task<U> {
        val task = Task<U>()
        handler = {r, e ->
            if(e == null) {
                try {
                    // See `map(f)` for an explanation of this cast.
                    val next = succeed(r as T)
                    next.handler = {r, e ->
                        if(e == null) {
                            task.finish(r as U)
                        } else {
                            task.fail(e)
                        }
                    }
                } catch(e: Throwable) {
                    task.fail(e)
                }
            } else {
                try {
                    val next = fail(e)
                    next.handler = { r, e ->
                        if(e == null) {
                            task.finish(r as U)
                        } else {
                            task.fail(e)
                        }
                    }
                } catch(e: Throwable) {
                    task.fail(e)
                }
            }
        }
        return task
    }

    /** Runs the provided function whether the task succeeds or not. */
    fun always(f: (T?, Throwable?) -> Unit): Task<T> {
        val task = Task<T>()
        handler = {r, e ->
            try {
                f(r, e)
                if(e === null) {
                    // See `map(f)` for an explanation of this cast.
                    task.finish(r as T)
                } else {
                    task.fail(e)
                }
            } catch(e: Throwable) {
                task.fail(e)
            }
        }
        return task
    }

    /** The last result passed through the task, if any. */
    val result: T? get() = cachedResult

    /** The last error passed through the task, if any. */
    val error: Throwable? get() = cachedError

    /** The current task result handler. */
    var handler: ((T?, Throwable?) -> Unit)? = null
        set(v) {
            field = v
            if(state != TaskState.waiting) {
                v?.invoke(result, error)
            }
        }

    private var cachedResult: T? = null
    private var cachedError: Throwable? = null
    private var state = TaskState.waiting
}
