// The capture log's limits.
//
// This is the route that hands Medha's notification stream to the agent,
// which is the side with a corpus and embeddings. It is also the largest
// amount of personal material Medha has ever made readable in one place, so
// the bounds are not housekeeping — they are the difference between a
// working set and a permanent record of everything that ever notified you.
import com.adabala.medha.connect.CapturePolicy

var failures = 0
var checks = 0

fun check(what: String, ok: Boolean) {
    checks++
    if (ok) println("  ok    $what")
    else { println("  FAIL  $what"); failures++ }
}

fun <T> eq(what: String, actual: T, expected: T) {
    checks++
    if (actual == expected) println("  ok    $what")
    else {
        println("  FAIL  $what\n          expected: $expected\n          actual:   $actual")
        failures++
    }
}

fun main() {
    println("CapturePolicyTest")

    println("\npageSize — a ceiling that cannot be argued out of")
    eq("absent gives the default", CapturePolicy.pageSize(null), CapturePolicy.PAGE_DEFAULT)
    eq("zero gives the default", CapturePolicy.pageSize(0), CapturePolicy.PAGE_DEFAULT)
    eq("negative gives the default", CapturePolicy.pageSize(-5), CapturePolicy.PAGE_DEFAULT)
    eq("a sane ask is honoured", CapturePolicy.pageSize(50), 50)
    eq("the maximum is honoured exactly", CapturePolicy.pageSize(CapturePolicy.PAGE_MAX),
        CapturePolicy.PAGE_MAX)
    eq("over the maximum is clamped, not refused",
        CapturePolicy.pageSize(999_999), CapturePolicy.PAGE_MAX)
    check("no input produces a page bigger than the cap",
        listOf(null, -1, 0, 1, 200, 1001, Int.MAX_VALUE)
            .all { CapturePolicy.pageSize(it) <= CapturePolicy.PAGE_MAX })
    check("nor a non-positive page, which would stall a cursor walk forever",
        listOf(null, -1, 0, 1, Int.MAX_VALUE).all { CapturePolicy.pageSize(it) > 0 })

    println("\ncursor — an opaque number the caller may get wrong")
    eq("absent starts at the beginning", CapturePolicy.cursor(null), 0L)
    eq("negative starts at the beginning, it is not an error",
        CapturePolicy.cursor(-7L), 0L)
    eq("a real cursor passes through", CapturePolicy.cursor(4321L), 4321L)
    eq("zero is a real cursor", CapturePolicy.cursor(0L), 0L)
    check("never negative", listOf(null, -1L, 0L, 99L).all { CapturePolicy.cursor(it) >= 0L })

    println("\ncutoff — the retention window")
    val now = 1_763_251_200_000L
    eq("is exactly the window back", CapturePolicy.cutoff(now), now - CapturePolicy.RETAIN_MS)
    check("and the window is days, not an accident of arithmetic",
        CapturePolicy.RETAIN_MS == CapturePolicy.RETAIN_DAYS * 24L * 60 * 60 * 1000)
    check("something inside the window survives",
        now - 3 * 24 * 60 * 60 * 1000L > CapturePolicy.cutoff(now))
    check("something outside it does not",
        now - 30 * 24 * 60 * 60 * 1000L < CapturePolicy.cutoff(now))

    println("\noverBy — the row ceiling catches what the clock does not")
    eq("an empty log is under", CapturePolicy.overBy(0), 0)
    eq("exactly at the cap is under", CapturePolicy.overBy(CapturePolicy.MAX_ROWS), 0)
    eq("one over is one to drop", CapturePolicy.overBy(CapturePolicy.MAX_ROWS + 1), 1)
    // A burst is the case that matters: trimming one row per insert never
    // catches up, so the whole overage is returned at once.
    eq("a burst is dropped whole, not one per insert",
        CapturePolicy.overBy(CapturePolicy.MAX_ROWS + 900), 900)
    check("never negative", (0..10).all { CapturePolicy.overBy(it) >= 0 })

    println("\nnextCursor — where the caller resumes from")
    eq("a full page resumes after its last row", CapturePolicy.nextCursor(4312L, 4312L), 4312L)
    eq("a short page resumes after its last row too",
        CapturePolicy.nextCursor(99L, 150L), 99L)
    // An empty page cannot supply a last row, and handing back the caller's
    // own `since` is what makes a client ask forever.
    eq("an empty page resumes at the head", CapturePolicy.nextCursor(null, 7000L), 7000L)
    eq("an empty log resumes at zero", CapturePolicy.nextCursor(null, 0L), 0L)
    // The one case where head is BELOW the old cursor: the log was cleared
    // on revocation, so everything the old cursor pointed past is gone and
    // a walk from zero returns exactly what exists.
    eq("after a clear the cursor goes back to zero, deliberately",
        CapturePolicy.nextCursor(null, 0L), 0L)

    println("\nhasMore — come straight back, or wait")
    check("a short page is the end", !CapturePolicy.hasMore(12, 200, 12L, 12L))
    check("an empty page is the end", !CapturePolicy.hasMore(0, 200, null, 500L))
    check("a full page with rows above it is not",
        CapturePolicy.hasMore(200, 200, 200L, 4000L))
    check("a full page that happens to reach the head IS the end",
        !CapturePolicy.hasMore(200, 200, 200L, 200L))
    // Two separate queries, so both races have to be safe. An insert between
    // them pushes head above the page; a prune between them can pull it
    // below. Neither may invent a page that is not there.
    check("an insert between the two queries reports more",
        CapturePolicy.hasMore(200, 200, 200L, 201L))
    check("a prune between them does not", !CapturePolicy.hasMore(200, 200, 900L, 300L))
    // The `returned >= limit` term earns its place HERE and nowhere else.
    // Every correctness check above passes without it -- a mutation probe
    // dropped the term and the suite stayed green -- because a short page's
    // last id always equals the head. What it actually prevents is a client
    // being driven into a tight loop by ordinary traffic: `more` has to mean
    // "there is a known backlog", not "a notification arrived while I was
    // answering you". A row that lands in that window is the next poll's
    // business.
    check("a SHORT page does not say come back, even with a row just added",
        !CapturePolicy.hasMore(12, 200, 12L, 13L))
    check("nor with a whole burst just added",
        !CapturePolicy.hasMore(12, 200, 12L, 900L))
    check("while a full page with the same gap does",
        CapturePolicy.hasMore(200, 200, 200L, 201L))

    println("\nA cursor walk sees every row exactly once")
    // The property that actually matters, asserted against a simulated log
    // rather than argued for: ids with gaps (pruning), a page size that
    // divides the count and one that does not, and a walk driven only by
    // what the route hands back.
    listOf(1, 3, 7, 200).forEach { page ->
        val ids = (1L..40L).filter { it % 3L != 0L }          // gaps, as pruning leaves
        val head = ids.max()
        val seen = ArrayList<Long>()
        var cursor = 0L
        var rounds = 0
        while (rounds++ < 1000) {
            val pageRows = ids.filter { it > cursor }.take(CapturePolicy.pageSize(page))
            seen += pageRows
            val last = pageRows.lastOrNull()
            cursor = CapturePolicy.nextCursor(last, head)
            if (!CapturePolicy.hasMore(pageRows.size, CapturePolicy.pageSize(page), last, head)) break
        }
        eq("page=$page: every row, once, in order", seen, ids)
        check("page=$page: terminated rather than spun", rounds < 1000)
    }
    // And the invariant the empty-page branch rests on: the query is
    // `id > since`, so an empty page means nothing sits above `since`.
    // Without this, resuming at `head` could skip rows.
    check("an empty page implies head <= since, so resuming at head skips nothing",
        (0L..50L).all { since ->
            val ids = (1L..40L).filter { it % 3L != 0L }
            val pageRows = ids.filter { it > since }.take(CapturePolicy.pageSize(null))
            pageRows.isNotEmpty() || ids.max() <= since
        })

    println("\nThe two bounds are independent")
    // A quiet month hits the clock; a busy week hits the ceiling. Either
    // alone leaves the other case unbounded.
    check("the row cap is a real number, not off",
        CapturePolicy.MAX_ROWS in 100..100_000)
    check("the retention window is a real number, not off",
        CapturePolicy.RETAIN_DAYS in 1..90)

    println("\n" + "-".repeat(50))
    if (failures == 0) println("CapturePolicyTest: all $checks checks passed")
    else { println("CapturePolicyTest: $failures of $checks FAILED"); kotlin.system.exitProcess(1) }
}
