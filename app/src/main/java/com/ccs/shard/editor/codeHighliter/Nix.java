package com.ccs.shard.editor.codeHighliter;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public class Nix extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "assert", "else", "if", "in", "inherit", "let", "rec", "then",
        "with", "or"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "builtins", "true", "false", "null", "import", "derivation",
        "toPath", "path", "readFile", "readDir", "fetchTarball",
        "fetchurl", "fetchgit", "fetchFromGitHub", "toString", "toInt",
        "toFloat", "typeOf", "isNull", "isBool", "isInt", "isFloat",
        "isString", "isPath", "isList", "isFunction", "isAttrs",
        "length", "head", "tail", "elemAt", "removeAttrs", "hasAttr",
        "attrNames", "attrValues", "getAttr", "mapAttrs", "filterAttrs",
        "foldl'", "foldl", "all", "any", "concatLists", "concatMap",
        "unique", "sort", "lessThan", "bitAnd", "bitOr", "bitXor",
        "builtins", "toJSON", "fromJSON", "toXML", "toYAML",
        "hashString", "baseNameOf", "dirOf", "storePath", "derivation",
        "derivationStrict", "placeholder", "substitute", "substituteAll",
        "writeTextFile", "writeShellScript", "writeShellScriptBin",
        "writeScript", "writeScriptBin", "writeShellScriptBin",
        "runCommand", "runCommandLocal", "runCommandNoCC", "runCommandCC",
        "mkShell", "mkShellNoCC", "fetchurl", "fetchzip", "fetchpatch",
        "fetchFromBitbucket", "fetchFromGitLab", "fetchFromGitHub",
        "fetchFromSavannah", "fetchFromRepoOrCvs", "fetchDebianPatch",
        "fetchMavenArtifact", "fetchMavenArtifacts", "buildEnv",
        "buildFHSEnv", "buildFHSEnvBubblewrap", "buildFHSEnvChroot",
        "buildFHSUserEnv", "buildFHSUserEnvBubblewrap", "buildFHSUserEnvChroot"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "true", "false", "null", "builtins", "self", "super"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "#.*"; }

    @Override
    protected String getBlockCommentStart() { return null; }

    @Override
    protected String getBlockCommentEnd() { return null; }

    @Override
    protected String getStringPattern() { return "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|''[\\s\\S]*?''"; }

    @Override
    protected String getCharPattern() { return "'[^'\\\\]'|'\\\\.'"; }

    @Override
    protected String getAnnotationPattern() { return "\\$\\{[^}]*\\}"; }
}
