package ar.com.brilla.trivia

import ar.com.brilla.trivia.core.*
import ar.com.brilla.trivia.access.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CoreTest {
    private var time=0L
    private val bank=(1L..20L).map { Question(it,"Pregunta $it",listOf("París")) }
    private fun engine(c:GameConfig=GameConfig(rounds=1,random=false,sendIntervalMs=2000),ts:List<Template> = Templates.defaults())=TriviaEngine(c,schedule(bank,c,Random(3)),ts) { time }
    private fun until(e:TriviaEngine,phase:Phase):List<String> {
        val sent=mutableListOf<String>()
        repeat(50000) {
            if(e.phase==phase) return sent
            e.tick()?.let { p->sent+=p.text; e.acknowledge(p.id,true) }
            time+=100
            if(!e.active && e.phase!=phase) error("Terminó antes: ${e.phase} ${e.reason}")
        }; error("No llegó a $phase: ${e.status}")
    }
    private fun answer(e:TriviaEngine,id:String="1",name:String="Pedro",text:String="París")=e.receive(ChatMessage(id,name,text))
    @Test fun defaultsMatchRequested() { val c=GameConfig(); assertEquals(10,c.rounds);assertEquals(10000,c.answerMs);assertEquals(5000,c.intervalMs);assertEquals(1,c.points);assertTrue(c.repeat&&c.random&&c.multipleWinners&&c.showRanking) }
    @Test fun importsSixSamples() { assertEquals(6,Importer.parse(Importer.sample).questions.size) }
    @Test fun imports500() { val r=Importer.parse((1..500).joinToString("\n\n") { " A = Pregunta $it\n B = Respuesta $it " });assertEquals(500,r.questions.size);assertTrue(r.errors.isEmpty()) }
    @Test fun duplicatesAndAlternatives() { val r=Importer.parse("A= Capital\nB= París || Paris\nA= CAPITAL\nB= otra");assertEquals(1,r.questions.size);assertEquals(1,r.duplicates);assertEquals(2,r.questions[0].answers.size) }
    @Test fun malformedImportIsReported() { val r=Importer.parse("B= x\nA= una\nA= otra\nZ= nope"); assertTrue(r.errors.size>=3); assertTrue(r.questions.isEmpty()) }
    @Test fun appendPreservesExisting() { val old=Importer.parse(Importer.sample).questions; assertEquals(0,Importer.parse(Importer.sample,old).questions.size) }
    @Test(expected=IllegalArgumentException::class) fun emptyBankBlocked() { schedule(emptyList(),GameConfig()) }
    @Test fun intervalLimitValidated() { assertNotNull(GameConfig(sendIntervalMs=1).error());assertNull(GameConfig(sendIntervalMs=2000).error()) }
    @Test fun invalidSettingsBlocked() { assertNotNull(GameConfig(rounds=0).error());assertNotNull(GameConfig(answerMs=999).error());assertNotNull(GameConfig(points=101).error()) }
    @Test fun repeats20Into30() { val c=GameConfig(rounds=30,random=false);val p=schedule(bank,c);assertEquals(30,p.size);assertEquals(bank,p.take(20));assertEquals(bank.take(10),p.drop(20)) }
    @Test fun oneQuestionCanRepeat() { assertEquals(50,schedule(bank.take(1),GameConfig(rounds=50)).size) }
    @Test fun stopsAtExhaustionWhenConfigured() { assertEquals(20,schedule(bank,GameConfig(rounds=50,repeat=false)).size) }
    @Test fun randomCyclesDoNotRepeatWithinCycle() { repeat(100) { val p=schedule(bank,GameConfig(rounds=50),Random(it)); assertEquals(20,p.take(20).map { q->q.id }.distinct().size); assertEquals(20,p.drop(20).take(20).map { q->q.id }.distinct().size);assertEquals(50,p.size) } }
    @Test fun nextCyclesCanBeSequential() { val p=schedule(bank,GameConfig(rounds=40,randomCycles=false),Random(2)); assertEquals(bank,p.drop(20)) }
    @Test fun timerStartsAfterConfirmation() { val e=engine();val first=e.tick()!!;time=9000;assertEquals(Phase.WELCOME,e.phase);assertEquals(0,e.remaining());e.acknowledge(first.id,true);until(e,Phase.ANSWERS);assertTrue(e.remaining() in 9800..10000) }
    @Test fun unconfirmedQuestionHasNoAnswerWindow() { val e=engine();var p=e.tick()!!;while(e.phase!=Phase.QUESTION) {e.acknowledge(p.id,true);time+=3000;p=e.tick()!!};time+=20000;assertFalse(answer(e));assertEquals(p,e.flight);assertEquals(Phase.QUESTION,e.phase) }
    @Test fun oneFlightNoUnlimitedQueue() { val e=engine();val p=e.tick();repeat(100) {time+=1000;assertNull(e.tick())};assertEquals(p,e.flight) }
    @Test fun respectsSendInterval() { val e=engine();val p=e.tick()!!;e.acknowledge(p.id,true);assertNull(e.tick());time=1999;assertNull(e.tick());time=2000;assertNotNull(e.tick()) }
    @Test fun wrongThenCorrectScores() { val e=engine();until(e,Phase.ANSWERS);assertFalse(answer(e,text="Londres"));assertTrue(answer(e,id="2"));assertEquals(1,e.scores["Pedro"]) }
    @Test fun multipleWinners() { val e=engine();until(e,Phase.ANSWERS);assertTrue(answer(e));assertTrue(answer(e,"2","María"));assertEquals(2,e.scores.size) }
    @Test fun onePointPerRound() { val e=engine();until(e,Phase.ANSWERS);answer(e);assertFalse(answer(e,"2"));assertEquals(1,e.scores["Pedro"]) }
    @Test fun duplicateEventIgnored() { val e=engine();until(e,Phase.ANSWERS);answer(e);assertFalse(answer(e,"1","Otro"));assertFalse(e.scores.containsKey("Otro")) }
    @Test fun noOwnOrAmbiguousScores() { val e=engine();until(e,Phase.ANSWERS);assertFalse(e.receive(ChatMessage("1","Pedro","París",own=true)));assertFalse(e.receive(ChatMessage("2",null,"París",reliable=false)));assertTrue(e.scores.isEmpty()) }
    @Test fun exactDeadlineRejects() { val e=engine();until(e,Phase.ANSWERS);time+=e.remaining();assertFalse(answer(e));e.tick();assertEquals(Phase.RESULT,e.phase) }
    @Test fun freezeAndResume() { val e=engine();until(e,Phase.ANSWERS);time+=2500;e.pause("chat incorrecto");val left=e.remaining();time+=60000;assertEquals(left,e.remaining());assertFalse(answer(e));assertTrue(e.resume());assertEquals(left,e.remaining());assertTrue(answer(e,"2")) }
    @Test fun failedSendPausesWithoutRetry() { val e=engine();val p=e.tick()!!;e.acknowledge(p.id,false);assertTrue(e.paused&&e.uncertain);assertFalse(e.resume());time+=10000;assertNull(e.tick());assertEquals(p,e.flight) }
    @Test fun explicitReceiptDoesNotResend() { val e=engine();val p=e.tick()!!;e.acknowledge(p.id,false);e.confirmVisiblePublication();assertNull(e.flight);assertTrue(e.resume());time+=2500;assertNotEquals(p,e.tick()) }
    @Test fun stopPreventsAnySend() { val e=engine();e.tick();e.stop();time+=100000;assertNull(e.tick());assertNull(e.flight);assertFalse(e.active) }
    @Test fun restoreAlwaysPaused() { val e=engine();until(e,Phase.ANSWERS);answer(e);val s=e.snapshot();time+=90000;val r=TriviaEngine.restore(s){time};assertTrue(r.paused);assertEquals(s.remainingMs,r.remaining());assertEquals(1,r.scores["Pedro"]);assertNull(r.tick()) }
    @Test fun restoreInFlightIsUncertain() { val e=engine();e.tick();val r=TriviaEngine.restore(e.snapshot()){time};assertTrue(r.uncertain&&r.paused);assertFalse(r.resume()) }
    @Test fun normalizationStrict() { assertEquals(normalized("  PARÍS  "),normalized("paris"));assertNotEquals(normalized("París",false),normalized("Paris",false));assertNotEquals(normalized("París"),normalized("París!")) }
    @Test fun singleWinnerMode() { val e=engine(GameConfig(rounds=1,multipleWinners=false));until(e,Phase.ANSWERS);answer(e);assertFalse(answer(e,"2","María")) }
    private fun fullGame(count:Int):TriviaEngine {
        val e=engine(GameConfig(rounds=count,random=false,sendIntervalMs=2000))
        for(i in 1..count) {until(e,Phase.ANSWERS);assertEquals(i,e.round);answer(e,"$i");time+=e.remaining();e.tick()}
        val final=until(e,Phase.FINISHED);assertTrue(final.any { it.contains("CAMPEÓN") });assertEquals(count,e.scores["Pedro"]);assertEquals(count,e.history.size);assertEquals(count,e.round);repeat(10){time+=50000;assertNull(e.tick())};return e
    }
    @Test fun exactly10Rounds() { fullGame(10) }
    @Test fun exactly20Rounds() { fullGame(20) }
    @Test fun exactly30RoundsWith20Questions() { fullGame(30) }
    @Test fun exactly50Rounds() { fullGame(50) }
    @Test fun tiesAnnounceAllChampions() { val e=engine();until(e,Phase.ANSWERS);answer(e);answer(e,"2","María");time+=e.remaining();val text=until(e,Phase.FINISHED).joinToString();assertTrue(text.contains("TENEMOS CAMPEONES"));assertEquals(setOf("Pedro","María"),e.champions().map {it.name}.toSet());assertEquals(listOf(1,1),e.ranking().map {it.rank}) }
    @Test fun noWinnersDoesNotInventChampion() {val e=engine();until(e,Phase.ANSWERS);time+=e.remaining();assertTrue(until(e,Phase.FINISHED).joinToString().contains("sin ganadores"));assertTrue(e.champions().isEmpty())}
    @Test fun customTemplateAndEmoji() {val e=engine(ts=Templates.defaults().map {if(it.key=="question")it.copy(text="✨ Ronda {numero}: {pregunta}") else it});assertTrue(until(e,Phase.ANSWERS).joinToString().contains("✨ Ronda 1: Pregunta 1"))}
    @Test fun unknownVariableRejected() {assertNotNull(Templates.error("{no_existe}"));assertNull(Templates.error("💖 {pregunta}"))}
    @Test fun chunksKeepSurrogatePairs() {val chunks=Templates.chunks("✨"+"😀".repeat(301),100);assertTrue(chunks.all {it.codePointCount(0,it.length)<=100});assertEquals("✨"+"😀".repeat(301),chunks.joinToString(""))}
    @Test fun oversizedAnnouncementStopsSafely() {val e=engine(ts=Templates.defaults().map {if(it.key=="welcome")it.copy(text="a".repeat(60001)) else it});assertEquals(Phase.STOPPED,e.phase);assertNull(e.tick())}
    @Test fun trackerBaselinesOldMessages() {val t=MessageTracker();val old=ChatMessage("1","Pedro","París");assertTrue(t.poll(listOf(old)).isEmpty());assertTrue(t.poll(listOf(old)).isEmpty())}
    @Test fun trackerRequiresStableObservationAndDeduplicates() {val t=MessageTracker();t.baseline(emptyList());val m=ChatMessage("1","Pedro","París");assertTrue(t.poll(listOf(m)).isEmpty());assertEquals(listOf(m),t.poll(listOf(m)));assertTrue(t.poll(listOf(m)).isEmpty())}
    private fun node(path:String,parent:String?,id:String,text:String,editable:Boolean=false)=UiNode(path,parent,"ar.com.brilla.fixture:id/$id",text,"","android.widget.TextView",editable,false,true,false,0)
    @Test fun consecutiveMessagesSameContainerReconstructSender() {val f=Frame("ar.com.brilla.fixture",3,listOf(node("0",null,"message_row",""),node("0/0","0","sender_name","Pedro"),node("0/1","0","message_text","Londres"),node("0/2","0","message_text","París")));val m=Adapters.fixture.messages(f);assertEquals(listOf("Pedro","Pedro"),m.map {it.sender});val e=engine();until(e,Phase.ANSWERS);m.forEach(e::receive);assertEquals(1,e.scores["Pedro"])}
    @Test fun ambiguousContainerDoesNotScore() {val f=Frame("ar.com.brilla.fixture",3,listOf(node("0",null,"message_row",""),node("0/0","0","sender_name","Pedro"),node("0/1","0","sender_name","María"),node("0/2","0","message_text","París")));assertFalse(Adapters.fixture.messages(f).single().reliable)}
    @Test fun missingSenderNeverInheritedFromUnrelatedRow() {val f=Frame("ar.com.brilla.fixture",3,listOf(node("0",null,"root",""),node("0/0","0","message_row",""),node("0/0/0","0/0","sender_name","Pedro"),node("0/0/1","0/0","message_text","Londres"),node("0/1","0","message_row",""),node("0/1/0","0/1","message_text","París")));assertNull(Adapters.fixture.messages(f).last().sender)}
    @Test fun identityRejectsDifferentChatAppWindowAndMissingField() {val f=Frame("ar.com.brilla.fixture",1,listOf(node("0",null,"chat_title","Grupo"),node("1",null,"field","",true)));val b=Binding(f.pkg,f.nodes[0].id,"Grupo",f.nodes[1].id,f.nodes[1].type,1);assertNull(Adapters.fixture.inspect(f,b));assertNotNull(Adapters.fixture.inspect(f.copy(pkg="other"),b));assertNotNull(Adapters.fixture.inspect(f.copy(window=2),b));assertNotNull(Adapters.fixture.inspect(f.copy(nodes=f.nodes.drop(1)),b));assertNotNull(Adapters.fixture.inspect(f.copy(nodes=f.nodes.take(1)),b))}
}
