package com.martmists.multiplatform.graphql

import com.martmists.multiplatform.graphql.ext.gqlName
import kotlin.enums.EnumEntries
import kotlin.reflect.*

class SchemaBuilder {
    private val typeMap = mutableMapOf<KType, Schema.TypeDefinition<*>>()
    private val enumMap = mutableMapOf<KType, Schema.EnumDefinition<*>>()
    private val queries = mutableMapOf<String, Schema.OperationDefinition<*>>()
    private val mutations = mutableMapOf<String, Schema.OperationDefinition<*>>()
    private val requestedTypes = mutableSetOf<KType>()

    class BackedPropertyBuilder<T, R> internal constructor(private val name: String, private val type: KType, private val prop: KProperty1<T, R>) {
        private var rule: suspend T.(SchemaRequestContext) -> Boolean = { true }

        /**
         * Adds an access rule to this property. The rule will be called on the object instance, and if it returns false,
         * the property will not be included in the response.
         */
        fun accessRule(rule: suspend T.(SchemaRequestContext) -> Boolean) {
            this.rule = rule
        }

        internal fun build(): Schema.PropertyDefinition<T, R> {
            return Schema.PropertyDefinition(name, type, rule, emptyMap()) { prop.get(this) }
        }
    }

    class PropertyBuilder<T, R> internal constructor(private val name: String, private val type: KType) {
        private var rule: suspend T.(SchemaRequestContext) -> Boolean = { true }
        private var resolver: (suspend T.(SchemaRequestContext) -> R)? = null
        private val arguments = mutableMapOf<String, KType>()

        /**
         * Adds an argument to this property.
         */
        inline fun <reified A> argument(name: String) = argument<A>(name, typeOf<A>())
        fun <A> argument(name: String, type: KType): SchemaRequestContext.() -> A {
            arguments[name] = type
            return { this.variable(name) }
        }

        /**
         * Adds an access rule to this property. The rule will be called on the object instance, and if it returns false,
         * the property will not be included in the response.
         */
        fun accessRule(rule: suspend T.(SchemaRequestContext) -> Boolean) {
            this.rule = rule
        }

        /**
         * Sets the resolver for this property. The resolver will be called on the object instance, and its result will be
         * returned in the response.
         */
        fun resolver(getter: suspend T.(SchemaRequestContext) -> R) {
            this.resolver = getter
        }

        internal fun build(): Schema.PropertyDefinition<T, R> {
            require(resolver != null) { "Property $name has no getter" }
            return Schema.PropertyDefinition(name, type, rule, arguments, resolver!!)
        }
    }

    inner class TypeBuilder<T> internal constructor(private val name: String, private val type: KType) {
        private val properties = mutableMapOf<String, Schema.PropertyDefinition<T, *>>()

        /**
         * Registers a property on this type. You must set the `resolver` function for this property.
         */
        inline fun <reified R> property(name: String, noinline builder: PropertyBuilder<T, R>.() -> Unit) = property(name, typeOf<R>(), builder)

        /**
         * Registers a bound property on this type.
         */
        inline fun <reified R> property(prop: KProperty1<T, R>, noinline builder: BackedPropertyBuilder<T, R>.() -> Unit = {}) = property(prop.name, typeOf<R>(), prop, builder)
        fun <R> property(name: String, type: KType, builder: PropertyBuilder<T, R>.() -> Unit) {
            requestedTypes.add(type)
            val propBuilder = PropertyBuilder<T, R>(name, type)
            propBuilder.builder()
            properties[name] = propBuilder.build()
        }
        fun <R> property(name: String, type: KType, prop: KProperty1<T, R>, builder: BackedPropertyBuilder<T, R>.() -> Unit) {
            requestedTypes.add(type)
            val propBuilder = BackedPropertyBuilder(name, type, prop)
            propBuilder.builder()
            properties[name] = propBuilder.build()
        }

        internal fun build(): Schema.TypeDefinition<T> {
            return Schema.TypeDefinition(name, type, properties)
        }
    }

    class OperationBuilder<T> internal constructor(private val name: String, private val type: KType) {
        private var rule: suspend (SchemaRequestContext) -> Boolean = { true }
        private var resolver: (suspend (SchemaRequestContext) -> T)? = null
        private val arguments = mutableMapOf<String, KType>()

        // TODO: Delegate version of `argument`?

        /**
         * Adds an argument to this operation.
         */
        inline fun <reified A> argument(name: String) = argument<A>(name, typeOf<A>())
        fun <A> argument(name: String, type: KType): SchemaRequestContext.() -> A {
            arguments[name] = type
            return {
                this.variable<A>(name)
            }
        }

        /**
         * Adds an access rule to this operation. If it returns false,
         * the operation will not be executed.
         */
        fun accessRule(rule: suspend (SchemaRequestContext) -> Boolean) {
            this.rule = rule
        }

        /**
         * Adds a resolver to this operation.
         */
        fun resolver(executor: suspend (SchemaRequestContext) -> T) {
            this.resolver = executor
        }

        internal fun build(): Schema.OperationDefinition<T> {
            require(resolver != null) { "Operation $name has no executor" }
            return Schema.OperationDefinition(name, type, rule, arguments, resolver!!)
        }
    }

    /**
     * Registers a type to the type system. All fields you wish to expose must be exposed manually.
     */
    inline fun <reified T : Any> type(noinline block: TypeBuilder<T>.() -> Unit) = type(typeOf<T>(), block)
    fun <T : Any> type(type: KType, block: TypeBuilder<T>.() -> Unit) {
        val typeBuilder = TypeBuilder<T>(type.gqlName, type)
        typeBuilder.block()
        typeMap[type] = typeBuilder.build()
    }

    /**
     * Registers an enum to the type system. This will only expose the name of the enum value.
     */
    inline fun <reified T : Enum<T>> enum(entries: EnumEntries<T>) = enum(entries, typeOf<T>())
    fun <T : Enum<T>> enum(entries: EnumEntries<T>, type: KType) {
        enumMap[type] = Schema.EnumDefinition(type.toString(), type, entries)
    }

    /**
     * Defines a query operation to the schema.
     */
    inline fun <reified T> query(name: String, noinline block: OperationBuilder<T>.() -> Unit) = query(name, typeOf<T>(), block)
    fun <T> query(name: String, type: KType, block: OperationBuilder<T>.() -> Unit) {
        requestedTypes.add(type)
        val operationBuilder = OperationBuilder<T>(name, type)
        operationBuilder.block()
        queries[name] = operationBuilder.build()
    }

    /**
     * Defines a mutation operation to the schema.
     */
    inline fun <reified T> mutation(name: String, noinline block: OperationBuilder<T>.() -> Unit) = mutation(name, typeOf<T>(), block)
    fun <T> mutation(name: String, type: KType, block: OperationBuilder<T>.() -> Unit) {
        requestedTypes.add(type)
        val operationBuilder = OperationBuilder<T>(name, type)
        operationBuilder.block()
        mutations[name] = operationBuilder.build()
    }

    internal fun build(): Schema {
        for (type in requestedTypes) {
            if (!typeMap.containsKey(type) && !enumMap.containsKey(type)) {
                if ((type.classifier as KClass<*>?)?.simpleName !in EXCLUDED_CLASSES.map { it.simpleName }) {  // TODO: Switch to qualifiedName once supported in JS
                    throw IllegalStateException("Type $type was returned, but not defined in the schema!")
                }
            }
        }

        return Schema(typeMap, enumMap, queries, mutations)
    }

    companion object {
        private val EXCLUDED_CLASSES = arrayOf(
            String::class,
            Int::class,
            Long::class,
            Float::class,
            Double::class,
            Boolean::class,
            List::class,
            Array::class,
        )
    }
}
