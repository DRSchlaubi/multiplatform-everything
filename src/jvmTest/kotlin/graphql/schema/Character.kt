package graphql.schema

sealed class Character(
    val id: String,
    val name: String,
    val friends: List<String>,
    val appearsIn: List<Number>,
    val secretBackstory: String?,
)