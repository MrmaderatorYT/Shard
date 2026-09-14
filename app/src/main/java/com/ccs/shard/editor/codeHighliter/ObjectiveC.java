package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class ObjectiveC extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "auto", "break", "case", "char", "const", "continue", "default", "do",
        "double", "else", "enum", "extern", "float", "for", "goto", "if",
        "inline", "int", "long", "register", "restrict", "return", "short",
        "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
        "unsigned", "void", "volatile", "while", "_Bool", "_Complex", "_Imaginary",
        "id", "Class", "SEL", "IMP", "BOOL", "YES", "NO", "nil", "NULL",
        "self", "super", "_cmd", "release", "retain", "autorelease", "dealloc",
        "init", "new", "copy", "mutableCopy", "hash", "description",
        "performSelector", "respondsToSelector", "conformsToProtocol",
        "isKindOfClass", "isMemberOfClass", "isProxy"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "NSObject", "NSString", "NSArray", "NSMutableArray", "NSDictionary",
        "NSMutableDictionary", "NSSet", "NSMutableSet", "NSNumber",
        "NSInteger", "NSUInteger", "CGFloat", "CGPoint", "CGSize", "CGRect",
        "NSData", "NSMutableData", "NSURL", "NSDate", "NSError", "NSNull",
        "NSValue", "NSException", "NSAutoreleasePool", "NSRunLoop",
        "NSNotificationCenter", "NSUserDefaults", "NSBundle", "NSProcessInfo",
        "NSDateFormatter", "NSNumberFormatter", "NSDateComponents",
        "NSCalendar", "NSTimeZone", "NSLocale", "NSCharacterSet",
        "NSPredicate", "NSSortDescriptor", "NSBlockOperation",
        "NSOperationQueue", "NSThread", "NSCondition", "NSLock"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "nil", "NULL", "YES", "NO", "self", "super"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "//.*"; }

    @Override
    protected String getBlockCommentStart() { return "/*"; }

    @Override
    protected String getBlockCommentEnd() { return "*/"; }

    @Override
    protected String getStringPattern() { return "@\"[^\"]*\"|\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\""; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "@interface|@implementation|@protocol|@property|@synthesize|@dynamic|@selector|@encode|@autoreleasepool|@available"; }

    @Override
    protected String getFunctionPattern() { return "\\b(\\-|\\+)\\s*\\([^)]*\\)\\s*([a-zA-Z_][a-zA-Z0-9_]*)"; }
}
