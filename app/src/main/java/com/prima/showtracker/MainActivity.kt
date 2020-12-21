package com.prima.showtracker

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
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
import androidx.compose.foundation.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AmbientContext
import androidx.compose.ui.platform.AmbientDensity
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import com.prima.showtracker.ui.green600

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
// - better flow and suspension
// - error handling
// - make it look cool
// - ability to change status
// - file structure

enum class TvShowStatus {
    WATCHING, PAUSED, ENDED, MOVIE
}

val TvShowStatusColors = mapOf<Int, Color>(
    TvShowStatus.WATCHING.ordinal to green600,
    TvShowStatus.PAUSED.ordinal to Color.Yellow,
    TvShowStatus.ENDED.ordinal to Color.Red
)

val tvShowStatusFriendly = mapOf<Int, String>(
    TvShowStatus.WATCHING.ordinal to "Watching",
    TvShowStatus.PAUSED.ordinal to "Paused",
    TvShowStatus.ENDED.ordinal to "Ended"
)

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
    fun getAll(): Flow<List<TvShow>>

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
    fun getAll(): Flow<List<TvShow>>{
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

    lateinit var tvShows: Flow<List<TvShow>>

    var tvShow: MutableState<TvShow?> = mutableStateOf(null)
        private set

    init {
        database = AppDatabase.getDatabase(application)
        repository = TvShowRepository(database.tvShowDao())

        viewModelScope.launch {
            tvShows = repository.getAll()
        }
    }

    suspend fun add(tvShow: TvShow){
        viewModelScope.launch(Dispatchers.IO){
            repository.add(tvShow = tvShow)
        }
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
        }
    }

    suspend fun update(tvShow: TvShow){
        viewModelScope.launch {
            Log.d("parser", "${tvShow.seasonTracker} / ${tvShow.id}")
            repository.update(tvShow)
        }
    }
}


class MainActivity : AppCompatActivity() {
    val appViewModel by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShowTrackerTheme(darkTheme = true) {
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
    val c = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary)
    val tvShows = appViewModel.tvShows.collectAsState(initial = listOf())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Show Tracker") },
                backgroundColor = topAppBarColor(),
                actions = {
                    IconButton(
                        onClick = { navController.navigate(route = PAGES.SHOWS_CREATE) },
                    ) {
                        Icon(imageVector = Icons.Default.AddCircle, tint = MaterialTheme.colors.primary)
                    }
                }
            )
        },
    ){
        val modifier = Modifier.padding(it)

        LazyColumn{
            items(tvShows.value){
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
            modifier = Modifier
                .preferredHeight(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onEdit),
            alignment = Alignment.TopStart
        )

        Spacer(modifier = Modifier.width(20.dp))

        Column(){
            Text(tvShow.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(tvShow.genres ?: "", style = MaterialTheme.typography.subtitle1)
            Spacer(modifier = Modifier.height(10.dp))

            Text("S: ${tvShow.seasonTracker}")
            Text("E: ${tvShow.episodeTracker}")

//            Spacer(modifier = Modifier.height(10.dp))
//
//            Text(tvShowStatusFriendly[tvShow.status]!!,
//                fontSize = 10.sp,
//                modifier = Modifier
//                    .clip(RoundedCornerShape(10.dp))
//                    .background(TvShowStatusColors[tvShow.status]!!)
//                    .padding(5.dp)
//            )
        }
    }
}

@Composable
fun PageShowsCreate(navController: NavController, appViewModel: AppViewModel){
    var imdbUrlLink by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    val context = AmbientContext.current
    val toast = Toast.makeText(context, "Failed to process", Toast.LENGTH_SHORT)

    val onbtnclick: () -> Unit = {
        coroutineScope.launch(Dispatchers.Main){
            isProcessing = true
            val tvShow = parseIMDBLink(imdbUrlLink)
            if (tvShow == null) {
                isProcessing = false
                toast.show()
                return@launch
            }

            appViewModel.add(tvShow)
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                backgroundColor = topAppBarColor(),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack)
                    }
                }
            )
        }
    )  {
        if(isProcessing){
            Text("Processing")
        } else {

            Column(
                modifier = Modifier.padding(it).padding(10.dp).fillMaxWidth(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Text("IMDB Link")
                Spacer(modifier = Modifier.height(10.dp))

                TextField(value = imdbUrlLink, onValueChange = { imdbUrlLink = it }, placeholder = {
                    Text(
                        "Insert IMDB link here"
                    )
                })
                Spacer(modifier = Modifier.height(10.dp))

                Button(onClick = onbtnclick) {
                    Text("Add")
                }
            }
        }
    }

}

@Composable
fun PageShowsEdit(navController: NavController, appViewModel: AppViewModel, tvShowId: String) {
    val coroutineScope = rememberCoroutineScope()
    val tvShow = appViewModel.tvShow
    val seasonTracker: MutableState<String> = remember {
        mutableStateOf(tvShow.value?.seasonTracker?.toString() ?: "0")
    }
    val episodeTracker: MutableState<String> = remember {
        mutableStateOf(tvShow.value?.episodeTracker?.toString() ?: "0")
    }
    val isSaving = remember { mutableStateOf(false) }

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
        isSaving.value = true
        if (tvShow.value != null) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                backgroundColor = topAppBarColor(),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack)
                    }
                }
            )
        }
    ) {
        if (tvShow.value == null) {
            Text("loading")
        } else if (isSaving.value) {
            Text("Saving")
        } else {
            val x = tvShow.value!!
            val density = AmbientDensity.current.density
            val linear = Brush.verticalGradient(
                if (MaterialTheme.colors.isLight) listOf(Color.Transparent, Color.White) else listOf(Color.Transparent, Color.Black),
                startY = 150f * density
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize(1f)) {
                Box(modifier = Modifier.fillMaxWidth(1f).height(250.dp)) {
                    CoilImage(data = x.imdbPosterUrl, modifier = Modifier.fillMaxHeight(1f).fillMaxWidth(1f))
                    Box(modifier = Modifier.background(linear).fillMaxSize(1f))
                }

                Text(x.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(x.genres ?: "", style = MaterialTheme.typography.subtitle1)

                Spacer(modifier = Modifier.preferredHeight(30.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.width(250.dp)
                ) {
                    Text("Season", modifier = Modifier.fillMaxWidth(0.4f))
                    TextField(
                        value = seasonTracker.value,
                        onValueChange = { seasonTracker.value = it },
                        backgroundColor = Color.Transparent,
                        modifier = Modifier.fillMaxWidth(0.5f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }

                Spacer(modifier = Modifier.preferredHeight(10.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.width(250.dp)
                ) {
                    Text("Episode", modifier = Modifier.fillMaxWidth(0.4f))
                    TextField(
                        value = episodeTracker.value,
                        onValueChange = { episodeTracker.value = it },
                        backgroundColor = Color.Transparent,
                        modifier = Modifier.fillMaxWidth(0.5f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }

                Spacer(modifier = Modifier.preferredHeight(40.dp))

                Row() {
                    Button(onClick = onSave) {
                        Icon(imageVector = Icons.Default.Check)
                        Text("Save")
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    Button(onClick = onRemove) {
                        Icon(imageVector = Icons.Default.Delete)
                        Text("Remove")
                    }
                }
            }
        }
    }
}

suspend fun parseIMDBLink(imdbUrl: String): TvShow?{
    if(imdbUrl.isBlank()){
        return null
    }

    var doc: Document? = withContext(Dispatchers.IO) {
        try{
            Jsoup.connect(imdbUrl).get()
        }
        catch (exception: java.lang.IllegalArgumentException){
            null
        }
        catch (exception: IOException){
            null
        }
    }

    if(doc == null){
        return null
    }

    var posterUrl: String? = null
    var title: String? = null
    var subtext: String? = null
    var genres: String? = null

    if(imdbUrl.contains("m.imdb.com")){
        // handle mobile
        posterUrl = doc.selectFirst("#titleOverview > div.media.titlemain__overview-media--mobile > a > img").attr("src")
        title = doc.selectFirst("#titleOverview > div.media.overview-top > div > h1").text()
        subtext = doc.selectFirst("#titleOverview > div.media.overview-top > div > p > span.itemprop").text()
        genres = subtext

    } else {
        // desktop
        posterUrl = doc.selectFirst("div.poster > a > img").attr("src")
        title = doc.selectFirst("div.title_wrapper > h1").text()
        subtext = doc.selectFirst("div.titleBar > div.title_wrapper > div.subtext").text()

        val splited_subtext = subtext.split("|")

        val maxSize = splited_subtext.size
        val selectedSize =
            when(maxSize){
                1 -> 0
                0 -> 0
                else -> { maxSize - 2 }
            }

        genres = splited_subtext[selectedSize].trim()
    }

    Log.d("parser", "$posterUrl")
    Log.d("parser", "$title")
    Log.d("parser", "$subtext")
    Log.d("parser", "$genres")

    return TvShow(
        id = 0,
        title = title,
        imdbUrl = imdbUrl,
        imdbPosterUrl = posterUrl,
        genres = genres,
        updatedAt = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
    )
}

@Composable
fun topAppBarColor(): Color{
    if(MaterialTheme.colors.isLight){
        return Color.White
    }

    return Color.Black
}

