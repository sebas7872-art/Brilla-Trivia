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
}
