// FIX: Remove explicit type arguments
// WITH_STDLIB

fun f(p: Int): List<String>? {
    return if (p > 0) {
        print("a")
        listOf<caret><String>()
    }
    else {
        null
    }
}