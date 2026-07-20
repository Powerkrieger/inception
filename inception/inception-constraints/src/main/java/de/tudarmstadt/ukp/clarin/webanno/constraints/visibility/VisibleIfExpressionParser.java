/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.constraints.visibility;

import java.util.ArrayList;
import java.util.List;

import de.tudarmstadt.ukp.clarin.webanno.constraints.visibility.VisibleIfExpression.And;
import de.tudarmstadt.ukp.clarin.webanno.constraints.visibility.VisibleIfExpression.Equals;
import de.tudarmstadt.ukp.clarin.webanno.constraints.visibility.VisibleIfExpression.In;
import de.tudarmstadt.ukp.clarin.webanno.constraints.visibility.VisibleIfExpression.Not;
import de.tudarmstadt.ukp.clarin.webanno.constraints.visibility.VisibleIfExpression.Or;

/**
 * A tiny, deterministic, non-scripting recursive-descent parser for {@code visibleIf} feature
 * visibility expressions.
 *
 * <pre>
 * expr       := orExpr
 * orExpr     := andExpr ( '||' andExpr )*
 * andExpr    := unaryExpr ( '&amp;&amp;' unaryExpr )*
 * unaryExpr  := '!' unaryExpr | primary
 * primary    := '(' expr ')' | comparison
 * comparison := IDENT ( '==' STRING
 *                     | '!=' STRING
 *                     | 'in' list
 *                     | 'not' 'in' list )
 * list       := '[' ']' | '[' STRING (',' STRING)* ']'
 * </pre>
 *
 * Feature names are plain identifiers ({@code [A-Za-z_][A-Za-z0-9_]*}); values are double-quoted
 * strings. No arbitrary code execution is possible: the grammar has no function calls, no field or
 * method access, and no way to reference anything but feature names and string literals.
 */
public final class VisibleIfExpressionParser
{
    private VisibleIfExpressionParser()
    {
        // Utility class
    }

    public static VisibleIfExpression parse(String aExpression) throws VisibleIfSyntaxException
    {
        var tokens = new Lexer(aExpression).tokenize();
        var parser = new Parser(tokens, aExpression);
        var expression = parser.parseOr();
        parser.expectEnd();
        return expression;
    }

    private enum TokenType
    {
        IDENT, STRING, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, EQEQ, NEQ, ANDAND, OROR, BANG, EOF
    }

    private record Token(TokenType type, String text, int position) {}

    private static final class Lexer
    {
        private final String input;
        private int pos;

        Lexer(String aInput)
        {
            input = aInput == null ? "" : aInput;
        }

        List<Token> tokenize() throws VisibleIfSyntaxException
        {
            var tokens = new ArrayList<Token>();
            while (true) {
                skipWhitespace();
                if (pos >= input.length()) {
                    tokens.add(new Token(TokenType.EOF, "", pos));
                    return tokens;
                }

                var start = pos;
                var c = input.charAt(pos);

                if (c == '(') {
                    pos++;
                    tokens.add(new Token(TokenType.LPAREN, "(", start));
                }
                else if (c == ')') {
                    pos++;
                    tokens.add(new Token(TokenType.RPAREN, ")", start));
                }
                else if (c == '[') {
                    pos++;
                    tokens.add(new Token(TokenType.LBRACKET, "[", start));
                }
                else if (c == ']') {
                    pos++;
                    tokens.add(new Token(TokenType.RBRACKET, "]", start));
                }
                else if (c == ',') {
                    pos++;
                    tokens.add(new Token(TokenType.COMMA, ",", start));
                }
                else if (c == '=' && peek(1) == '=') {
                    pos += 2;
                    tokens.add(new Token(TokenType.EQEQ, "==", start));
                }
                else if (c == '!' && peek(1) == '=') {
                    pos += 2;
                    tokens.add(new Token(TokenType.NEQ, "!=", start));
                }
                else if (c == '&' && peek(1) == '&') {
                    pos += 2;
                    tokens.add(new Token(TokenType.ANDAND, "&&", start));
                }
                else if (c == '|' && peek(1) == '|') {
                    pos += 2;
                    tokens.add(new Token(TokenType.OROR, "||", start));
                }
                else if (c == '!') {
                    pos++;
                    tokens.add(new Token(TokenType.BANG, "!", start));
                }
                else if (c == '"') {
                    tokens.add(readString());
                }
                else if (isIdentStart(c)) {
                    tokens.add(readIdent());
                }
                else {
                    throw new VisibleIfSyntaxException(
                            "Unexpected character '" + c + "' at position " + pos);
                }
            }
        }

        private char peek(int aOffset)
        {
            var i = pos + aOffset;
            return i < input.length() ? input.charAt(i) : '\0';
        }

        private void skipWhitespace()
        {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private boolean isIdentStart(char aChar)
        {
            return Character.isLetter(aChar) || aChar == '_';
        }

        private boolean isIdentPart(char aChar)
        {
            return Character.isLetterOrDigit(aChar) || aChar == '_';
        }

        private Token readIdent()
        {
            var start = pos;
            while (pos < input.length() && isIdentPart(input.charAt(pos))) {
                pos++;
            }
            return new Token(TokenType.IDENT, input.substring(start, pos), start);
        }

        private Token readString() throws VisibleIfSyntaxException
        {
            var start = pos;
            pos++; // consume opening quote
            var sb = new StringBuilder();
            while (true) {
                if (pos >= input.length()) {
                    throw new VisibleIfSyntaxException(
                            "Unterminated string literal starting at position " + start);
                }
                var c = input.charAt(pos);
                if (c == '"') {
                    pos++;
                    return new Token(TokenType.STRING, sb.toString(), start);
                }
                if (c == '\\' && pos + 1 < input.length() && (peek(1) == '"' || peek(1) == '\\')) {
                    sb.append(peek(1));
                    pos += 2;
                    continue;
                }
                sb.append(c);
                pos++;
            }
        }
    }

    private static final class Parser
    {
        private final List<Token> tokens;
        private final String source;
        private int index;

        Parser(List<Token> aTokens, String aSource)
        {
            tokens = aTokens;
            source = aSource;
        }

        void expectEnd() throws VisibleIfSyntaxException
        {
            if (current().type() != TokenType.EOF) {
                throw error("Unexpected trailing input");
            }
        }

        VisibleIfExpression parseOr() throws VisibleIfSyntaxException
        {
            var left = parseAnd();
            while (current().type() == TokenType.OROR) {
                advance();
                var right = parseAnd();
                left = new Or(left, right);
            }
            return left;
        }

        private VisibleIfExpression parseAnd() throws VisibleIfSyntaxException
        {
            var left = parseUnary();
            while (current().type() == TokenType.ANDAND) {
                advance();
                var right = parseUnary();
                left = new And(left, right);
            }
            return left;
        }

        private VisibleIfExpression parseUnary() throws VisibleIfSyntaxException
        {
            if (current().type() == TokenType.BANG) {
                advance();
                return new Not(parseUnary());
            }
            return parsePrimary();
        }

        private VisibleIfExpression parsePrimary() throws VisibleIfSyntaxException
        {
            if (current().type() == TokenType.LPAREN) {
                advance();
                var inner = parseOr();
                expect(TokenType.RPAREN, "Expected closing ')'");
                return inner;
            }

            return parseComparison();
        }

        private VisibleIfExpression parseComparison() throws VisibleIfSyntaxException
        {
            var featureName = expect(TokenType.IDENT, "Expected a feature name").text();

            if (current().type() == TokenType.EQEQ) {
                advance();
                var value = expect(TokenType.STRING, "Expected a quoted string after '=='").text();
                return new Equals(featureName, value);
            }

            if (current().type() == TokenType.NEQ) {
                advance();
                var value = expect(TokenType.STRING, "Expected a quoted string after '!='").text();
                return new Not(new Equals(featureName, value));
            }

            if (current().type() == TokenType.IDENT && "in".equals(current().text())) {
                advance();
                var values = parseList();
                return new In(featureName, values);
            }

            if (current().type() == TokenType.IDENT && "not".equals(current().text())) {
                advance();
                if (!(current().type() == TokenType.IDENT && "in".equals(current().text()))) {
                    throw error("Expected 'in' after 'not'");
                }
                advance();
                var values = parseList();
                return new Not(new In(featureName, values));
            }

            throw error("Expected '==', '!=', 'in' or 'not in' after feature name '" + featureName
                    + "'");
        }

        private List<String> parseList() throws VisibleIfSyntaxException
        {
            expect(TokenType.LBRACKET, "Expected '[' to start a list");

            var values = new ArrayList<String>();
            if (current().type() != TokenType.RBRACKET) {
                values.add(expect(TokenType.STRING, "Expected a quoted string in list").text());
                while (current().type() == TokenType.COMMA) {
                    advance();
                    values.add(expect(TokenType.STRING, "Expected a quoted string in list").text());
                }
            }

            expect(TokenType.RBRACKET, "Expected ']' to close the list");
            return values;
        }

        private Token current()
        {
            return tokens.get(index);
        }

        private void advance()
        {
            if (index < tokens.size() - 1) {
                index++;
            }
        }

        private Token expect(TokenType aType, String aMessage) throws VisibleIfSyntaxException
        {
            if (current().type() != aType) {
                throw error(aMessage);
            }
            var token = current();
            advance();
            return token;
        }

        private VisibleIfSyntaxException error(String aMessage)
        {
            return new VisibleIfSyntaxException(
                    aMessage + " (at position " + current().position() + " in [" + source + "])");
        }
    }
}
