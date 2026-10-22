package com.example.localdocsearch

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader

// --- ROOM DATABASE SETUP WITH FTS4 ---

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val type: String
)

@Fts4(contentEntity = DocumentEntity::class)
@Entity(tableName = "documents_fts")
data class DocumentFtsEntity(
    val name: String,
    val content: String
)

@Entity(tableName = "tags", primaryKeys = ["docId", "tagName"])
data class TagEntity(
    val docId: Long,
    val tagName: String
)

@Dao
interface DocumentDao {
    @Insert
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Query("INSERT INTO documents_fts(docid, name, content) VALUES (:docId, :name, :content)")
    suspend fun insertFts(docId: Long, name: String, content: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity)

    @Query("""
        SELECT d.* FROM documents d 
        JOIN documents_fts fts ON d.id = fts.docid 
        WHERE fts.content MATCH :query
    """)
    fun searchDocuments(query: String): Flow<List<DocumentEntity>>

    @Query("""
        SELECT d.* FROM documents d
        JOIN tags t ON d.id = t.docId
        WHERE t.tagName = :tag
    """)
    fun getDocumentsByTag(tag: String): Flow<List<DocumentEntity>>

    @Query("SELECT DISTINCT tagName FROM tags")
    fun getAllTags(): Flow<List<String>>
}

@Database(entities = [DocumentEntity::class, DocumentFtsEntity::class, TagEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "doc_search_db").build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- MAIN ACTIVITY & UI ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.documentDao()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    
    val searchResults by if (selectedTag != null) {
        dao.getDocumentsByTag(selectedTag!!).collectAsState(initial = emptyList())
    } else {
        dao.searchDocuments(if (searchQuery.isEmpty()) "*" else searchQuery).collectAsState(initial = emptyList())
    }
    
    val allTags by dao.getAllTags().collectAsState(initial = emptyList())

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val fileName = getFileName(context, it) ?: "Unknown"
                        val fileType = fileName.substringAfterLast(".", "")
                        
                        val inputStream = context.contentResolver.openInputStream(it)
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        val rawContent = reader.use { r -> r.readText() }
                        
                        // Parse HTML/MHTML content to plain text using Jsoup
                        val cleanText = if (fileType.equals("html", true) || fileType.equals("mhtml", true)) {
                            Jsoup.parse(rawContent).text()
                        } else {
                            rawContent
                        }
                        
                        val docId = dao.insertDocument(DocumentEntity(uri = it.toString(), name = fileName, type = fileType))
                        dao.insertFts(docId, fileName, cleanText)
                        
                        // Auto extract some dummy tags or keywords for demonstration
                        if (cleanText.contains("Android", ignoreCase = true)) {
                            dao.insertTag(TagEntity(docId, "Android"))
                        }
                        dao.insertTag(TagEntity(docId, fileType.uppercase()))

                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(context, "Successfully indexed: $fileName", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(context, "Error indexing file: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("ðŸ“š Local Document Full-Text Search", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(10.dp))
        
        Button(onClick = { filePickerLauncher.launch(arrayOf("text/plain", "text/html", "application/x-mimearchive")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import Local File (TXT / HTML / MHTML)")
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { 
                selectedTag = null
                searchQuery = it 
            },
            label = { Text("Search keyword inside documents...") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Text("Tags (Click to filter):", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            allTags.forEach { tag ->
                FilterChip(
                    selected = selectedTag == tag,
                    onClick = { selectedTag = if (selectedTag == tag) null else tag },
                    label = { Text(tag) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text("Results:", style = MaterialTheme.typography.titleMedium)
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(searchResults) { doc ->
                Card(modifier = Modifier.fillMaxWidth().clickable { 
                    Toast.makeText(context, "Opening file: ${doc.name}", Toast.LENGTH_SHORT).show()
                }) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(doc.name, style = MaterialTheme.typography.bodyLarge)
                        Text("Type: ${doc.type.uppercase()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = it.getString(index)
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}
