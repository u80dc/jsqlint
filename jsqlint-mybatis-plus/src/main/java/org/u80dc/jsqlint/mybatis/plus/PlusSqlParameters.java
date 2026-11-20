package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.node.SqlNodeRenderer.BoundParameter;
import java.util.*;
import java.util.function.Function;

/** JDBC 占位符身份标签；字符串、标识符、注释和 dollar quote 内的问号不会被改写。 */
final class PlusSqlParameters {
    static String tag(String sql,int expected) {
        int[] index={0};String result=scan(sql,ignored->"?"+(++index[0]));
        if(index[0]!=expected)throw new IllegalArgumentException("SQL placeholder count does not match binding count");return result;
    }
    record Restored(String sql,List<BoundParameter> bindings) {}
    static Restored restore(String sql,List<BoundParameter> source) {
        var selected=new ArrayList<BoundParameter>();String result=scan(sql,token->{
            if(token.length()==1)throw new IllegalArgumentException("Unidentified plugin JDBC parameter");
            int index=Integer.parseInt(token.substring(1))-1;if(index<0||index>=source.size())throw new IllegalArgumentException("Invalid plugin parameter identity");
            var binding=source.get(index);selected.add(new BoundParameter(selected.size()+1,binding.expression(),binding.type(),binding.options(),binding.value(),binding.origin()));return "?";
        });return new Restored(result,List.copyOf(selected));
    }
    static String tagNew(String sql,int existing) {int[] next={existing};return scan(sql,token->token.length()==1?"?"+(++next[0]):token);}
    private static String scan(String sql,Function<String,String> parameter) {
        var result=new StringBuilder();int index=0;
        while(index<sql.length()) {
            char ch=sql.charAt(index);int end=index+1;
            if(ch=='\''||ch=='"'||ch=='`'||ch=='[') {
                char close=ch=='['?']':ch;boolean closed=false;
                while(end<sql.length()) {char current=sql.charAt(end++);if(current=='\\'&&end<sql.length()){end++;continue;}if(current==close){if(end<sql.length()&&sql.charAt(end)==close){end++;continue;}closed=true;break;}}
                if(!closed)throw new IllegalArgumentException("Unterminated SQL quoted token");
            } else if(sql.startsWith("--",index)) {end=sql.indexOf('\n',index+2);if(end<0)end=sql.length();}
            else if(sql.startsWith("/*",index)) {
                int depth=1;end=index+2;while(end<sql.length()&&depth>0){if(sql.startsWith("/*",end)){depth++;end+=2;}else if(sql.startsWith("*/",end)){depth--;end+=2;}else end++;}if(depth!=0)throw new IllegalArgumentException("Unterminated SQL comment");
            } else if(ch=='$') {
                int mark=index+1;while(mark<sql.length()&&(Character.isLetterOrDigit(sql.charAt(mark))||sql.charAt(mark)=='_'))mark++;
                if(mark<sql.length()&&sql.charAt(mark)=='$'){String delimiter=sql.substring(index,mark+1);int close=sql.indexOf(delimiter,mark+1);if(close<0)throw new IllegalArgumentException("Unterminated dollar quote");end=close+delimiter.length();}
            } else if(ch=='?') {
                while(end<sql.length()&&Character.isDigit(sql.charAt(end)))end++;
                result.append(parameter.apply(sql.substring(index,end)));index=end;continue;
            }
            result.append(sql,index,end);index=end;
        }
        return result.toString();
    }
}
