package at.hof.farmos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import at.hof.farmos.data.FieldWork
import at.hof.farmos.data.LogKind
import at.hof.farmos.ui.FieldWorkScreen
import at.hof.farmos.ui.NewEntryScreen
import at.hof.farmos.ui.AnimalDetailScreen
import at.hof.farmos.ui.CrashScreen
import at.hof.farmos.ui.AnimalListScreen
import at.hof.farmos.ui.AnimalNewScreen
import at.hof.farmos.ui.FarmTheme
import at.hof.farmos.ui.LocationDetailScreen
import at.hof.farmos.ui.LocationListScreen
import at.hof.farmos.ui.LogListScreen
import at.hof.farmos.ui.LoginScreen
import at.hof.farmos.ui.MainViewModel
import at.hof.farmos.ui.QuickLogScreen
import at.hof.farmos.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FarmTheme {
                var crash by remember { mutableStateOf(CrashLog.read(this)) }
                val report = crash
                if (report != null) {
                    CrashScreen(report, onContinue = { CrashLog.clear(this); crash = null })
                } else {
                    val vm: MainViewModel = viewModel()
                    val loggedIn by vm.loggedIn.collectAsState()
                    if (loggedIn) AppRoot(vm) else LoginScreen(vm)
                }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("animals", "Tiere", Icons.Default.Pets),
    Tab("locations", "Flächen", Icons.Default.Landscape),
    Tab("logs", "Einträge", Icons.Default.Description),
    Tab("settings", "Sync", Icons.Default.Sync),
)

private fun fieldWorkRoute(work: FieldWork, field: String? = null, plants: List<String> = emptyList()) =
    "fieldwork?work=${work.name}&field=${field ?: ""}&plants=${plants.joinToString(",")}"

private fun quickLogRoute(
    animals: List<String> = emptyList(),
    plants: List<String> = emptyList(),
    loc: String? = null,
    kind: LogKind? = null,
    camera: Boolean = false,
) = "quicklog?animals=${animals.joinToString(",")}&plants=${plants.joinToString(",")}" +
    "&loc=${loc ?: ""}&kind=${kind?.name ?: ""}&camera=$camera"

@Composable
private fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val ops by vm.pendingOps.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == "settings" && ops.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text(ops.size.toString()) } }) {
                                        Icon(tab.icon, null)
                                    }
                                } else Icon(tab.icon, null)
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        val bottom = PaddingValues(bottom = inner.calculateBottomPadding())
        NavHost(
            navController = nav,
            startDestination = "animals",
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
        ) {
            composable("animals") {
                AnimalListScreen(
                    vm,
                    onOpen = { nav.navigate("animal/$it") },
                    onNew = { nav.navigate("animal_new") },
                    onPhoto = { nav.navigate(quickLogRoute(camera = true)) },
                )
            }
            composable("locations") {
                LocationListScreen(
                    vm,
                    onOpen = { nav.navigate("location/$it") },
                    onPhoto = { nav.navigate(quickLogRoute(camera = true)) },
                )
            }
            composable("logs") {
                LogListScreen(
                    vm,
                    onNew = { nav.navigate("new") },
                    onPhoto = { nav.navigate(quickLogRoute(camera = true)) },
                )
            }
            composable("new") {
                // Nach der Auswahl ersetzt das Formular diese Seite (Zurück führt zur Liste).
                val replace: (String) -> Unit = { r -> nav.navigate(r) { popUpTo("new") { inclusive = true } } }
                NewEntryScreen(
                    onBack = { nav.popBackStack() },
                    onFieldWork = { w -> replace(fieldWorkRoute(w)) },
                    onQuickLog = { k, cam -> replace(quickLogRoute(kind = k, camera = cam)) },
                    onNewAnimal = { replace("animal_new") },
                )
            }
            composable(
                "fieldwork?work={work}&field={field}&plants={plants}",
                arguments = listOf(
                    navArgument("work") { type = NavType.StringType; defaultValue = FieldWork.SPRAYING.name },
                    navArgument("field") { type = NavType.StringType; defaultValue = "" },
                    navArgument("plants") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                val a = e.arguments
                FieldWorkScreen(
                    vm,
                    work = FieldWork.entries.firstOrNull { it.name == a?.getString("work") } ?: FieldWork.SPRAYING,
                    preField = a?.getString("field")?.ifBlank { null },
                    prePlants = a?.getString("plants").orEmpty().split(",").filter { it.isNotBlank() },
                    onDone = { nav.popBackStack() },
                )
            }
            composable("settings") { SettingsScreen(vm) }

            composable("animal/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                val id = e.arguments?.getString("id").orEmpty()
                AnimalDetailScreen(
                    vm, id,
                    onBack = { nav.popBackStack() },
                    onQuickLog = { kind, camera ->
                        nav.navigate(quickLogRoute(animals = listOf(id), kind = kind, camera = camera))
                    },
                )
            }
            composable("location/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                val id = e.arguments?.getString("id").orEmpty()
                LocationDetailScreen(
                    vm, id,
                    onBack = { nav.popBackStack() },
                    onOpen = { nav.navigate("location/$it") },
                    onFieldWork = { w, field, plants -> nav.navigate(fieldWorkRoute(w, field, plants)) },
                    onQuickLog = { kind, camera, isPlant ->
                        nav.navigate(
                            if (isPlant) quickLogRoute(plants = listOf(id), kind = kind, camera = camera)
                            else quickLogRoute(loc = id, kind = kind, camera = camera)
                        )
                    },
                )
            }
            composable("animal_new") {
                AnimalNewScreen(vm, onDone = { nav.popBackStack() })
            }
            composable(
                "quicklog?animals={animals}&plants={plants}&loc={loc}&kind={kind}&camera={camera}",
                arguments = listOf(
                    navArgument("animals") { type = NavType.StringType; defaultValue = "" },
                    navArgument("plants") { type = NavType.StringType; defaultValue = "" },
                    navArgument("camera") { type = NavType.BoolType; defaultValue = false },
                    navArgument("loc") { type = NavType.StringType; defaultValue = "" },
                    navArgument("kind") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                val a = e.arguments
                QuickLogScreen(
                    vm,
                    preAnimals = a?.getString("animals").orEmpty().split(",").filter { it.isNotBlank() },
                    prePlants = a?.getString("plants").orEmpty().split(",").filter { it.isNotBlank() },
                    startCamera = a?.getBoolean("camera") ?: false,
                    preLocation = a?.getString("loc")?.ifBlank { null },
                    preKind = a?.getString("kind")?.let { k -> LogKind.entries.firstOrNull { it.name == k } },
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}
