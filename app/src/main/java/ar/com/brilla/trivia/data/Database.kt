package ar.com.brilla.trivia.data

import android.content.Context
import androidx.room.*
import ar.com.brilla.trivia.core.*
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName="questions") data class QuestionRecord(@PrimaryKey(autoGenerate=true) val id: Long=0, val text: String, val answers: String)
@Entity(tableName="settings") data class SettingRecord(@PrimaryKey val key: String, val value: String)
@Entity(tableName="games") data class GameRecord(@PrimaryKey(autoGenerate=true) val id: Long=0, val date: Long, val state: String)
@Dao interface TriviaDao {
    @Query("SELECT * FROM questions ORDER BY id") fun questions(): List<QuestionRecord>
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun putQuestions(rows: List<QuestionRecord>)
    @Query("DELETE FROM questions WHERE id=:id") fun deleteQuestion(id: Long)
    @Query("DELETE FROM questions") fun clearQuestions()
    @Query("SELECT value FROM settings WHERE `key`=:key") fun value(key: String): String?
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun put(row: SettingRecord)
    @Query("DELETE FROM settings WHERE `key`=:key") fun delete(key: String)
    @Query("DELETE FROM settings") fun clearSettings()
    @Insert fun game(row: GameRecord)
    @Query("SELECT * FROM games ORDER BY date DESC LIMIT 100") fun games(): List<GameRecord>
    @Query("DELETE FROM games WHERE id NOT IN (SELECT id FROM games ORDER BY date DESC LIMIT 100)") fun trimGames()
    @Query("DELETE FROM games") fun clearGames()
}
@Database(entities=[QuestionRecord::class,SettingRecord::class,GameRecord::class],version=1,exportSchema=true)
abstract class TriviaDatabase: RoomDatabase() { abstract fun dao(): TriviaDao }

object Json {
    fun config(c: GameConfig): JSONObject = JSONObject().put("rounds",c.rounds).put("answerMs",c.answerMs).put("intervalMs",c.intervalMs).put("points",c.points)
        .put("random",c.random).put("repeat",c.repeat).put("randomCycles",c.randomCycles).put("exhaustFirst",c.exhaustFirst)
        .put("multipleWinners",c.multipleWinners).put("showRanking",c.showRanking).put("rankingSize",c.rankingSize).put("ignoreAccents",c.ignoreAccents)
        .put("sendIntervalMs",c.sendIntervalMs).put("messageLength",c.messageLength)
    fun config(j: JSONObject): GameConfig = GameConfig(j.optInt("rounds",10),j.optLong("answerMs",10000),j.optLong("intervalMs",5000),j.optInt("points",1),j.optBoolean("random",true),j.optBoolean("repeat",true),j.optBoolean("randomCycles",true),j.optBoolean("exhaustFirst",true),j.optBoolean("multipleWinners",true),j.optBoolean("showRanking",true),j.optInt("rankingSize",10),j.optBoolean("ignoreAccents",true),j.optLong("sendIntervalMs",3000),j.optInt("messageLength",500))
    fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    fun templates(ts: List<Template>): JSONArray = JSONArray(ts.map { JSONObject().put("key",it.key).put("text",it.text).put("enabled",it.enabled).put("order",it.order) })
    fun templates(a: JSONArray): List<Template> {
        val stored=(0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("key") }
        return Templates.defaults().map { t -> stored[t.key]?.let { t.copy(text=it.getString("text"),enabled=it.optBoolean("enabled",true),order=it.optInt("order",t.order)) } ?: t }
    }
    private fun question(q: Question)=JSONObject().put("id",q.id).put("text",q.text).put("answers",JSONArray(q.answers))
    private fun question(j: JSONObject)=Question(j.getLong("id"),j.getString("text"),strings(j.getJSONArray("answers")))
    private fun publication(p: Publication)=JSONObject().put("id",p.id).put("text",p.text)
    private fun publication(j: JSONObject)=Publication(j.getLong("id"),j.getString("text"))
    fun state(s: EngineState): String = JSONObject().put("config",config(s.config)).put("plan",JSONArray(s.plan.map(::question))).put("templates",templates(s.templates))
        .put("phase",s.phase.name).put("index",s.index).put("scores",JSONObject(s.scores)).put("history",JSONArray(s.history.map { JSONObject().put("number",it.number).put("question",it.question).put("answer",it.answer).put("winners",JSONArray(it.winners)) }))
        .put("winners",JSONArray(s.winners)).put("remaining",s.remainingMs).put("queue",JSONArray(s.queued.map(::publication))).put("flight",s.flight?.let(::publication))
        .put("serial",s.serial).put("seen",JSONArray(s.seen)).put("uncertain",s.uncertain).put("notice",s.notice).put("startedAt",s.startedAt).toString()
    fun state(raw: String): EngineState {
        val j=JSONObject(raw); val p=j.getJSONArray("plan"); val h=j.getJSONArray("history"); val q=j.getJSONArray("queue"); val scores=j.getJSONObject("scores")
        return EngineState(config(j.getJSONObject("config")),(0 until p.length()).map { question(p.getJSONObject(it)) },templates(j.getJSONArray("templates")),Phase.valueOf(j.getString("phase")),j.getInt("index"),scores.keys().asSequence().associateWith { scores.getInt(it) },(0 until h.length()).map { h.getJSONObject(it).let { r -> RoundResult(r.getInt("number"),r.getString("question"),r.getString("answer"),strings(r.getJSONArray("winners"))) } },strings(j.getJSONArray("winners")),j.getLong("remaining"),(0 until q.length()).map { publication(q.getJSONObject(it)) },j.optJSONObject("flight")?.let(::publication),j.getLong("serial"),strings(j.getJSONArray("seen")),j.optBoolean("uncertain"),j.optString("notice"),j.optLong("startedAt"))
    }
}
class Repository(context: Context) {
    private val db=Room.databaseBuilder(context.applicationContext,TriviaDatabase::class.java,"brilla_trivia.db").build()
    val dao=db.dao()
    fun initialize() {
        if(dao.value("initialized")==null) db.runInTransaction { add(Importer.parse(Importer.sample).questions); dao.put(SettingRecord("initialized","1")) }
    }
    fun questions()=dao.questions().map { Question(it.id,it.text,Json.strings(JSONArray(it.answers))) }
    fun add(q: List<Question>)=dao.putQuestions(q.map { QuestionRecord(it.id,it.text,JSONArray(it.answers).toString()) })
    fun config()=runCatching { Json.config(JSONObject(dao.value("config") ?: "{}")) }.getOrDefault(GameConfig())
    fun save(c:GameConfig) { require(c.error()==null); dao.put(SettingRecord("config",Json.config(c).toString())) }
    fun templates()=runCatching { Json.templates(JSONArray(dao.value("templates") ?: "[]")) }.getOrDefault(Templates.defaults())
    fun save(ts:List<Template>) { require(ts.all { Templates.error(it.text)==null }); dao.put(SettingRecord("templates",Json.templates(ts).toString())) }
    fun checkpoint(e: TriviaEngine)=dao.put(SettingRecord("checkpoint",Json.state(e.snapshot())))
    fun restore(clock:()->Long)=dao.value("checkpoint")?.let { TriviaEngine.restore(Json.state(it),clock) }
    fun archive(e: TriviaEngine) { dao.game(GameRecord(date=System.currentTimeMillis(),state=Json.state(e.snapshot()))); dao.trimGames() }
}
