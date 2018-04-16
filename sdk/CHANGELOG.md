# Graal SDK Changelog

This changelog summarizes major changes between Graal SDK versions. The main focus is on APIs exported by Graal SDK.

## Version 1.0
* Added Context.Builder#allowHostClassLoading to allow loading of new classes by the guest language.
* Added `Value.getSourceLocation()` to find a function `SourceSection`.

## Version 0.33
* Expose Runtime name as Engine#getImplementationName();
* Deprecate Context#exportSymbol, Context#importSymbol, Context#lookup use Context#getBindings, Context#getPolyglotBindings instead.
* Remove deprecated API Engine#getLanguage, Engine#getInstrument.
* Remove deprecated Language#isHost.
* Deprecate ProxyPrimitive without replacement.
* Added Context.Builder#allAccess that allows to declare that a context has all access by default, also for new access rights.

## Version 0.31

* Added Value#as(Class) and Value.as(TypeLiteral) to convert to Java types.
* Added Context#asValue(Object) to convert Java values back to the polyglot Value representation.
* Added Value#isProxyObject() and Value#asProxyObject().

## Version 0.29

* Introduced Context.enter() and Context.leave() that allows explicitly entering and leaving the context to improve performance of performing many simple operations.
* Introduced Value.executeVoid to allow execution of functions more efficiently if not return value is expected.


## Version 0.26

* Initial revision of the polyglot API introduced.
* Initial revision of the native image API introduced.
* Initial revision of the options API introduced.
