package ar.com.brilla.trivia

import ar.com.brilla.trivia.core.*
import ar.com.brilla.trivia.access.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CoreTest {
    private var time=0L
    private val bank=(1L..20L).map { Question(it,"Pregunta $it",listOf("París")) }
    private fun engine(c:GameConfig=GameConfig(rounds=1,random=false,sendIntervalMs=2000,answerMs=30_000),ts:List<Template> = Templates.defaults())=TriviaEngine(c,schedule(bank,c,Random(3)),ts) { time }
    private fun until(e:TriviaEngine,phase:Phase):List<String> {
        val sent=mutableListOf<String>()
        repeat(50000) {
            if(e.phase==phase) return sent
            e.tick()?.let { p->sent+=p.text; e.acknowledge(p.id,true) }
            time+=100
            if(!e.active && e.phase!=phase) error("Terminó antes: ${e.phase} ${e.reason}")
        }; error("No llegó a $phase: ${e.status}")
    }
    private fun answer(e:TriviaEngine,id:String="1",name:String="Pedro",text:String="París",own:Boolean=false)=e.receive(ChatMessage(id,name,text,own))
    @Test fun defaultsMatchRequested() { val c=GameConfig(); assertEquals(10,c.rounds);assertEquals(10000,c.answerMs);assertFalse(c.hostPlays) }
    @Test fun speedScoringEarlyVsLate() {
        val e=engine(); until(e,Phase.ANSWERS)
        time += 2000; assertTrue(answer(e,"a","Ayelén🏹"))
        time += 23000; assertTrue(answer(e,"b","Pedro"))
        val early = e.scores["Ayelén🏹"]!!; val late = e.scores["Pedro"]!!
        assertTrue(early > late); assertTrue(early >= 12); assertTrue(late <= 4)
    }
    @Test fun twoCorrectBothScoreByTime() {
        val e=engine(); until(e,Phase.ANSWERS)
        time += 1000; answer(e,"1","A")
        time += 5000; answer(e,"2","B")
        assertEquals(2, e.scores.size)
        assertTrue(e.scores["A"]!! > e.scores["B"]!!)
    }
    @Test fun wrongAnswerZero() { val e=engine(); until(e,Phase.ANSWERS); assertFalse(answer(e,text="Londres")); assertTrue(e.scores.isEmpty()) }
    @Test fun oneScorePerParticipant() { val e=engine(); until(e,Phase.ANSWERS); answer(e); assertFalse(answer(e,"2")); assertEquals(1, e.scores.size) }
    @Test fun lateAnswerRejected() { val e=engine(); until(e,Phase.ANSWERS); time += e.remaining() + 100; assertFalse(answer(e)) }
    @Test fun automaticMessageNeverScores() {
        val e=engine(GameConfig(rounds=1, hostPlays=true, hostName="Ayelén🏹", answerMs=30_000))
        until(e,Phase.ANSWERS)
        val auto = ChatMessage("auto", null, "🔻 ¡Tienen 30 segundos!", own=true, reliable=true)
        assertFalse(e.receive(auto))
        assertTrue(e.scores.isEmpty())
    }
    @Test fun hostManualScoresWhenEnabled() {
        val e=engine(GameConfig(rounds=1, hostPlays=true, hostName="Ayelén🏹", answerMs=30_000))
        until(e,Phase.ANSWERS)
        assertTrue(e.receive(ChatMessage("m1", null, "París", own=true, reliable=true)))
        assertEquals(1, e.scores.size)
        assertTrue(e.scores.containsKey("Ayelén🏹"))
    }
    @Test fun ambiguousNeverScores() { val e=engine(); until(e,Phase.ANSWERS); assertFalse(e.receive(ChatMessage("x", null, "París", reliable=false))); assertTrue(e.scores.isEmpty()) }
    @Test fun rankingByAccumulatedPoints() {
        val e=engine(GameConfig(rounds=2, random=false, answerMs=10_000))
        until(e,Phase.ANSWERS); time += 1000; answer(e,"1","Fast"); time += 8000; answer(e,"2","Slow"); time += e.remaining(); e.tick()
        until(e,Phase.ANSWERS); time += 500; answer(e,"3","Fast"); time += e.remaining(); e.tick()
        val r = e.ranking()
        assertEquals("Fast", r.first().name)
        assertTrue(r.first().points > r[1].points)
    }
    @Test fun pauseResumeDoesNotDuplicate() {
        val e=engine(); until(e,Phase.ANSWERS); time += 2000; answer(e); e.pause("test"); e.resume(); assertFalse(answer(e,"again"))
        assertEquals(1, e.scores.size)
    }

    @Test fun realCaseMelinaYamilaAyelénAllScoreIndependently() {
        val c = GameConfig(rounds=1, hostPlays=true, hostName="Ayelén🏹", multipleWinners=true, ignoreAccents=true, answerMs=30_000, points=15, random=false)
        val e = engine(c)
        until(e, Phase.ANSWERS)
        // Melina early
        time += 1500
        assertTrue(e.receive(ChatMessage("id-melina", "Melina", "Paris")))
        val melina = e.scores["Melina"]!!
        // Yamila later, with accent
        time += 7000
        assertTrue(e.receive(ChatMessage("id-yamila", "Yamila Paez", "París")))
        val yamila = e.scores["Yamila Paez"]!!
        // Ayelén manual own message
        time += 6000
        assertTrue(e.receive(ChatMessage("id-ayelen", null, "Paris", own=true, reliable=true)))
        val ayelen = e.scores["Ayelén🏹"]!!
        assertEquals(3, e.scores.size)
        assertTrue(melina > yamila)
        assertTrue(yamila > ayelen)
        assertTrue(melina in 10..15)
        assertTrue(ayelen >= 1)
        // No duplicates
        assertFalse(e.receive(ChatMessage("id-melina-2", "Melina", "Paris")))
        assertEquals(melina, e.scores["Melina"])
        assertEquals(setOf("Melina", "Yamila Paez", "Ayelén🏹"), e.scores.keys)
    }
    @Test fun automaticNeverScoresEvenIfOwn() {
        val e = engine(GameConfig(rounds=1, hostPlays=true, hostName="Ayelén🏹", answerMs=30_000, points=15))
        until(e, Phase.ANSWERS)
        // The time announcement is already marked as auto
        assertFalse(e.receive(ChatMessage("auto1", null, "⏳ ¡Tienen 30 segundos!", own=true, reliable=true)))
        assertTrue(e.scores.isEmpty())
    }
    @Test fun lateAndWrongDoNotScore() {
        val e = engine(GameConfig(rounds=1, answerMs=10_000, points=15))
        until(e, Phase.ANSWERS)
        assertFalse(e.receive(ChatMessage("w", "Pedro", "Londres")))
        time += e.remaining() + 50
        assertFalse(e.receive(ChatMessage("l", "Pedro", "París")))
        assertTrue(e.scores.isEmpty())
    }
    @Test fun rankingAccumulatesAcrossRounds() {
        val e = engine(GameConfig(rounds=2, random=false, answerMs=10_000, points=15, multipleWinners=true))
        until(e, Phase.ANSWERS)
        time += 1000; e.receive(ChatMessage("1", "Melina", "París"))
        time += 3000; e.receive(ChatMessage("2", "Yamila", "París"))
        time += e.remaining(); e.tick()
        until(e, Phase.ANSWERS)
        time += 500; e.receive(ChatMessage("3", "Melina", "París"))
        val r = e.ranking()
        assertEquals("Melina", r.first().name)
        assertTrue(r.first().points > r[1].points)
    }


    // --- Adapter / Tracker tests with synthetic snapshots ---
    private fun node(path: String, id: String, text: String, y: Int = 100, parent: String? = null, type: String = "TextView") =
        UiNode(path, parent, id, text, "", type, false, false, true, false, y)

    @Test fun adapterDoesNotAttributeNewMessageToOldSender() {
        // Old message from Melina (higher up), new message from Ayelén (own, no name)
        val old = node("0/1", "message_text", "hola", y=200, parent="0")
        val oldName = node("0/2", "sender_name", "Melina", y=190, parent="0")
        val newBody = node("0/5", "message_text", "Pacifico", y=600, parent="0/4")
        val frame = Frame("com.zhiliaoapp.musically", 1, listOf(old, oldName, newBody, node("0/4", "message_row", "", y=580, parent="0")))
        val msgs = Adapters.tiktok.messages(frame)
        val pacifico = msgs.find { it.text == "Pacifico" }
        assertNotNull(pacifico)
        // Must NOT be attributed to Melina
        assertTrue(pacifico!!.sender != "Melina")
        // If not reliably own, reliable should be false so it doesn't score under wrong name
        if (pacifico.sender == null && !pacifico.own) assertFalse(pacifico.reliable)
    }

    @Test fun threeSameTextDifferentSendersAreDistinct() {
        val m1 = node("a/1", "message_text", "7", y=100, parent="a")
        val n1 = node("a/2", "sender_name", "Yamila", y=90, parent="a")
        val m2 = node("b/1", "message_text", "7", y=300, parent="b")
        val n2 = node("b/2", "sender_name", "Melina", y=290, parent="b")
        val m3 = node("c/1", "message_text", "7", y=500, parent="c")
        val n3 = node("c/2", "sender_name", "Ayelén", y=490, parent="c")
        val frame = Frame("com.zhiliaoapp.musically", 1, listOf(m1,n1,m2,n2,m3,n3,
            node("a","message_row","",80,"root"), node("b","message_row","",280,"root"), node("c","message_row","",480,"root")))
        val msgs = Adapters.tiktok.messages(frame)
        val sevens = msgs.filter { it.text == "7" && it.reliable }
        assertEquals(3, sevens.size)
        assertEquals(setOf("Yamila","Melina","Ayelén"), sevens.map { it.sender }.toSet())
    }

    @Test fun trackerIgnoresBaselineAndReportsOnlyNew() {
        val tracker = MessageTracker()
        val old = listOf(ChatMessage("id1","Melina","hola"))
        tracker.baseline(old)
        val after = old + ChatMessage("id2","Ayelén","Pacifico", own=true, reliable=true)
        val fresh = tracker.poll(after)
        assertEquals(1, fresh.size)
        assertEquals("Pacifico", fresh[0].text)
        // Second poll of same should return nothing
        assertTrue(tracker.poll(after).isEmpty())
    }

    @Test fun rebaselineClearsPrevious() {
        val tracker = MessageTracker()
        tracker.baseline(listOf(ChatMessage("a","X","1")))
        tracker.rebaseline(listOf(ChatMessage("b","Y","2")))
        val fresh = tracker.poll(listOf(ChatMessage("b","Y","2"), ChatMessage("c","Z","3")))
        assertEquals(1, fresh.size)
        assertEquals("3", fresh[0].text)
    }

    @Test fun previousCorrectAnswerNotScoredAfterBaseline() {
        val e = engine(GameConfig(rounds=1, answerMs=30_000, multipleWinners=true, ignoreAccents=true, hostPlays=true, hostName="Ayelén🏹"))
        until(e, Phase.ANSWERS)
        val tracker = MessageTracker()
        val oldMsg = ChatMessage("old","Melina","Pacifico")
        tracker.baseline(listOf(oldMsg))
        val newMsg = ChatMessage("new", null, "Pacifico", own=true, reliable=true)
        val fresh = tracker.poll(listOf(oldMsg, newMsg))
        assertEquals(1, fresh.size)
        assertEquals("Pacifico", fresh[0].text)
        assertTrue(e.receive(fresh[0]))
        assertTrue(e.scores.containsKey("Ayelén🏹"))
        assertFalse(e.scores.containsKey("Melina"))
    }


    @Test fun hostManualReplyScoresWhenInferredOwnOnTikTok() {
        // Synthetic TikTok frame: no sender name, no own keyword → inferred own
        val body = node("r/1", "message_text", "Marte", y=400, parent="r")
        val row = node("r", "message_row", "", y=390, parent="root")
        val frame = Frame("com.zhiliaoapp.musically", 1, listOf(body, row))
        val msgs = Adapters.tiktok.messages(frame)
        val m = msgs.find { it.text == "Marte" }
        assertNotNull(m)
        assertTrue(m!!.own)
        assertTrue(m.reliable)
        // Engine attributes to host
        val e = engine(GameConfig(rounds=1, hostPlays=true, hostName="Ayelén🏹", answerMs=30_000, ignoreAccents=true))
        until(e, Phase.ANSWERS)
        assertTrue(e.receive(m))
        assertTrue(e.scores.containsKey("Ayelén🏹"))
        assertEquals(1, e.scores.size)
    }

    @Test fun automaticBotMessageStillDoesNotScore() {
        val e = engine(GameConfig(rounds=1, hostPlays=true, hostName="Ayelén🏹", answerMs=30_000))
        until(e, Phase.ANSWERS)
        // Simulate an auto announcement that was enqueued
        val auto = ChatMessage("auto-id", null, "⏳ ¡Tienen 30 segundos!", own=true, reliable=true)
        assertFalse(e.receive(auto))
        assertTrue(e.scores.isEmpty())
    }

}
