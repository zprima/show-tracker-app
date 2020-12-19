package com.prima.showtracker

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ScrollableColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigate
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import com.google.android.material.textfield.TextInputEditText
import com.prima.showtracker.ui.ShowTrackerTheme
import dev.chrisbanes.accompanist.coil.CoilImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.sql.Time
import androidx.activity.viewModels
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset

// main page is a list of shows
// there is and + button that goes to show create page
// each show has its edit page to increase season and episode
//
// Todo:
// - Navigation [done]
// - Link to go to create page [done]
// - Parsing the IMDB page [done]
// - DB records [done]
// - adding to db [done]
// - edit page [done]
// - update existing record [done]
// - removing from db [done]
// - make it look cool


enum class TvShowStatus {
    WATCHING, PAUSED, ENDED,
}

@Entity(tableName = "tv_shows")
data class TvShow(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "imdb_url") val imdbUrl: String,
    @ColumnInfo(name = "imdb_poster_url") val imdbPosterUrl: String,
    @ColumnInfo(name = "genres") val genres: String?,
    @ColumnInfo(name = "season_tracker") val seasonTracker: Int = 0,
    @ColumnInfo(name = "episode_tracker") val episodeTracker: Int = 0,
    @ColumnInfo(name = "updated_tracer_at") val updatedTrackerAt: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
    @ColumnInfo(name = "status") val status: Int = TvShowStatus.WATCHING.ordinal
)

@Dao
interface TvShowDao {
    @Query("SELECT * FROM tv_shows")
    suspend fun getAll(): List<TvShow>

    @Query("SELECT * FROM tv_shows WHERE id = :tvShowId")
    suspend fun findBy(tvShowId: Int): TvShow?

    @Insert
    suspend fun insert(vararg tvShow: TvShow)

    @Delete
    suspend fun delete(tvShow: TvShow)

    @Update
    suspend fun update(vararg tvShow: TvShow): Int
}

@Database(entities = arrayOf(TvShow::class), version = 2)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tvShowDao(): TvShowDao

    companion object{
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase{
            val tempInstance = INSTANCE
            if(tempInstance != null) {
                return tempInstance
            }

            synchronized(this){
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "show_tracer__database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                return instance
            }
        }
    }
}

class TvShowRepository(private val tvShowDao: TvShowDao){
    suspend fun getAll(): List<TvShow>{
        return tvShowDao.getAll()
    }

    suspend fun add(tvShow: TvShow){
        tvShowDao.insert(tvShow)
    }

    suspend fun remove(tvShow: TvShow){
        tvShowDao.delete(tvShow)
    }

    suspend fun findBy(tvShowId: Int): TvShow?{
        return tvShowDao.findBy(tvShowId)
    }

    suspend fun update(tvShow: TvShow){
        val x = tvShowDao.update(tvShow)
        Log.d("parser", "${x} ${tvShow.id} ${tvShow.seasonTracker}")
        x
    }
}

class AppViewModel(application: Application): AndroidViewModel(application){
    private val database: AppDatabase
    private val repository: TvShowRepository

    var tvShows: MutableState<List<TvShow>> = mutableStateOf(listOf())
        private set
    var tvShow: MutableState<TvShow?> = mutableStateOf(null)
        private set

    init {
        database = AppDatabase.getDatabase(application)
        repository = TvShowRepository(database.tvShowDao())

        viewModelScope.launch {
            tvShows.value = repository.getAll()
        }
    }

    suspend fun add(tvShow: TvShow){
        repository.add(tvShow = tvShow)
    }

    suspend fun findBy(tvShowId: Int): Job{
        return viewModelScope.launch {
            tvShow.value = repository.findBy(tvShowId)
            Log.d("parser", "${tvShow.value} for $tvShowId")
        }
    }

    suspend fun remove(tvShow: TvShow){
        viewModelScope.launch {
            repository.remove(tvShow)
            tvShows.value = repository.getAll()
        }
    }

    suspend fun update(tvShow: TvShow){
        viewModelScope.launch {
            Log.d("parser", "${tvShow.seasonTracker} / ${tvShow.id}")
            repository.update(tvShow)
            tvShows.value = repository.getAll()
        }
    }
}


class MainActivity : AppCompatActivity() {
    val appViewModel by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShowTrackerTheme(darkTheme = false) {
                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {
                    App(appViewModel)
                }
            }
        }
    }
}

sealed class PAGES() {
    companion object {
        const val SHOWS_INDEX = "pages/shows"
        const val SHOWS_CREATE = "pages/shows/create"
        const val SHOWS_EDIT = "pages/shows/edit"
    }
}

@Composable
fun App(appViewModel: AppViewModel){
    val navController = rememberNavController()

    NavHost(navController, startDestination = PAGES.SHOWS_INDEX){
        composable(PAGES.SHOWS_INDEX) { PageShows(navController, appViewModel) }
        composable(PAGES.SHOWS_CREATE) { PageShowsCreate(navController, appViewModel) }
        composable(PAGES.SHOWS_EDIT + "/{tvShowId}") { backStateEntry ->
            PageShowsEdit(navController, appViewModel, backStateEntry.arguments?.getString("tvShowId")!!)
        }
    }
}

@Composable
fun PageShows(navController: NavController, appViewModel: AppViewModel){
    val c = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
    val tvShows = appViewModel.tvShows.value

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shows Tracker") },
                actions = {
                    Button(
                        colors = c,
                        onClick = { navController.navigate(route = PAGES.SHOWS_CREATE) }) {
                        Icon(imageVector = Icons.Default.AddCircle, tint = Color.White)
                    }

                }
            )
        },
    ){
        val modifier = Modifier.padding(it)

        LazyColumn{
            items(tvShows){
                ShowItem(modifier = modifier, tvShow = it, onEdit = { navController.navigate(PAGES.SHOWS_EDIT + "/${it.id}" )})
            }
        }

    }
}

@Composable
fun ShowItem(modifier: Modifier, tvShow: TvShow, onEdit: () -> Unit){

    Row(modifier = modifier.padding(10.dp)){

        CoilImage(
            data = tvShow.imdbPosterUrl,
            modifier = Modifier.preferredHeight(200.dp).clip(RoundedCornerShape(20.dp)),
            alignment = Alignment.TopStart
        )

        Spacer(modifier = Modifier.width(20.dp))

        Column(){
            Text(tvShow.title.toUpperCase(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${tvShow.id}")

            Spacer(modifier = Modifier.height(10.dp))

            Text("S: ${tvShow.seasonTracker}")
            Text("E: ${tvShow.episodeTracker}")

            Spacer(modifier = Modifier.height(10.dp))

            Button(onClick = onEdit) {
                Text("Edit")
            }
        }
    }
}

@Composable
fun PageShowsCreate(navController: NavController, appViewModel: AppViewModel){
    var imdbUrlLink by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var successfullCreate by remember { mutableStateOf(false) }

    val onbtnclick: () -> Unit = {
        successfullCreate = false
        val job = coroutineScope.launch(Dispatchers.IO) {
            val tvShow = parseIMDBLink(imdbUrlLink)
            if(tvShow != null) {
                appViewModel.add(tvShow)
                successfullCreate = true
            }
        }

        job.invokeOnCompletion {
            Log.d("parser", "job done")
            coroutineScope.launch(Dispatchers.Main) { navController.popBackStack() }
        }
    }

    Scaffold(){
        Column(modifier = Modifier.padding(it).padding(10.dp)){
            Text("IMDB Link")

            TextField(value = imdbUrlLink, onValueChange = { imdbUrlLink = it }, placeholder = {
                Text(
                    "Insert IMDB link here"
                )
            })

            Button(onClick = onbtnclick) {
                Text("Add")
            }
        }
    }

}

@Composable
fun PageShowsEdit(navController: NavController, appViewModel: AppViewModel, tvShowId: String){
    val coroutineScope = rememberCoroutineScope()
    val tvShow = appViewModel.tvShow
    val seasonTracker: MutableState<String> = remember {mutableStateOf(tvShow.value?.seasonTracker?.toString() ?: "0") }
    val episodeTracker: MutableState<String> = remember {mutableStateOf(tvShow.value?.episodeTracker?.toString() ?: "0") }

    LaunchedEffect(subject = tvShowId, block = {
        appViewModel.findBy(tvShowId = tvShowId.toInt()).invokeOnCompletion {
            val x = tvShow.value?.seasonTracker?.toString()
            seasonTracker.value = x.toString()

            val y = tvShow.value?.episodeTracker?.toString()
            episodeTracker.value = y.toString()
        }
    })



    val onRemove: () -> Unit = {
            var job = coroutineScope.launch(Dispatchers.IO) {
                appViewModel.remove(tvShow.value!!)
            }
            job.invokeOnCompletion {
                coroutineScope.launch(Dispatchers.Main) {
                    navController.popBackStack()
                }
            }

    }

    val onSave: () -> Unit = {
        if(tvShow.value != null) {
            var job = coroutineScope.launch(Dispatchers.IO) {
                val x = tvShow.value!!
                val _tvShow = TvShow(
                    id = tvShowId.toInt(),
                    seasonTracker = seasonTracker.value.toInt(),
                    episodeTracker = episodeTracker.value.toInt(),
                    updatedTrackerAt = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC),
                    title = x.title,
                    imdbUrl = x.imdbUrl,
                    imdbPosterUrl = x.imdbPosterUrl,
                    genres = x.genres,
                    updatedAt = x.updatedAt,
                    status = x.status
                )
                appViewModel.update(_tvShow)
            }
            job.invokeOnCompletion {
                coroutineScope.launch(Dispatchers.Main) {
                    navController.popBackStack()
                }
            }
        }
    }

    if(tvShow.value == null){
        Text("loading")
    } else {
        val x = tvShow.value!!

        Column() {
            Text(x.title)

            Button(onClick = onRemove) {
                Text("Remove")
            }

            Spacer(modifier = Modifier.preferredHeight(20.dp))

            Text("Season")
            TextField(
                value = seasonTracker.value,
                onValueChange = { seasonTracker.value = it }
            )

            Text("Episode")
            TextField(
                value = episodeTracker.value,
                onValueChange = { episodeTracker.value = it }
            )

            Button(onClick = onSave) {
                Text("Save")
            }

        }
    }

}

suspend fun parseIMDBLink(imdbUrl: String): TvShow?{
    if(imdbUrl.isBlank()) return null

    var doc: Document?

    try {
        doc = Jsoup.connect(imdbUrl).get()
    }
    catch (exception: IOException){
        return null
    }

    val posterUrl = doc.selectFirst("div.poster > a > img").attr("src")
    val title = doc.selectFirst("div.title_wrapper > h1").text()
    val subtext = doc.selectFirst("div.title_wrapper > div").text()

    Log.d("parser", "$posterUrl")
    Log.d("parser", "$title")
    Log.d("parser", "$subtext")

    val genres = subtext.split("|")[0]

    return TvShow(
        id = 0,
        title = title,
        imdbUrl = imdbUrl,
        imdbPosterUrl = posterUrl,
        genres = genres,
        updatedAt = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
    )
}

