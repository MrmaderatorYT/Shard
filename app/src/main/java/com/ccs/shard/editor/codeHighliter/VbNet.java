package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class VbNet extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "AddHandler", "AddressOf", "Alias", "And", "AndAlso", "As", "Boolean",
        "ByRef", "Byte", "ByVal", "Call", "Case", "Catch", "CBool", "CByte",
        "CChar", "CDate", "CDbl", "CDec", "Char", "CInt", "Class", "CLng",
        "CObj", "Const", "Continue", "CSByte", "CShort", "CSng", "CStr",
        " CType", "CUInt", "CULng", "CUShort", "Date", "Decimal", "Declare",
        "Default", "Delegate", "Dim", "DirectCast", "Do", "Double", "Each",
        "Else", "ElseIf", "End", "EndIf", "Enum", "Erase", "Error", "Event",
        "Exit", "False", "Finally", "For", "Friend", "Function", "Get",
        "GetType", "GetXMLNamespace", "Global", "GoSub", "GoTo", "Handles",
        "If", "Implements", "Imports", "In", "Inherits", "Integer", "Interface",
        "Is", "IsNot", "Let", "Lib", "Like", "Long", "Loop", "Me", "Mod",
        "Module", "MustInherit", "MustOverride", "MyBase", "MyClass", "Namespace",
        "Narrowing", "New", "Next", "Not", "Nothing", "NotInheritable",
        "NotOverridable", "Object", "Of", "On", "Operator", "Option", "Optional",
        "Or", "OrElse", "Overloads", "Overridable", "Overrides", "ParamArray",
        "Partial", "Private", "Property", "Protected", "Public", "RaiseEvent",
        "ReadOnly", "ReDim", "Rem", "RemoveHandler", "Resume", "Return", "SByte",
        "Select", "Set", "Shadows", "Shared", "Short", "Single", "Static",
        "Step", "Stop", "String", "Structure", "Sub", "SyncLock", "Then",
        "Throw", "To", "True", "Try", "TryCast", "TypeOf", "UInteger", "ULong",
        "UShort", "Using", "Variant", "Wend", "When", "While", "Widening",
        "With", "WithEvents", "WriteOnly", "Xor"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "Boolean", "Byte", "Char", "Date", "Decimal", "Double", "Integer",
        "Long", "Object", "SByte", "Short", "Single", "String", "UInteger",
        "ULong", "UShort", "Array", "List", "Dictionary", "HashSet",
        "Console", "Math", "String", "Exception", "StringBuilder"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "True", "False", "Nothing", "Nothing"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "'.*"; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"[^\"]*\""; }

    @Override
    protected String getAnnotationPattern() { return "<[^>]+>"; }

    @Override
    protected String getFunctionPattern() { return "\\b(Function|Sub|Property)\\s+([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
