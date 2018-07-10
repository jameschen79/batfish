package org.batfish.datamodel.questions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.Interface;

/**
 * Enables specification of groups of interfaces in various questions.
 *
 * <p>Currently supported example specifiers:
 *
 * <ul>
 *   <li>Loopback.* —> all interfaces with matching names
 *   <li>desc:KK.* -> all interfaces whose description matches KK.*
 * </ul>
 *
 * <p>In the future, we might need other tags and boolean expressions over tags.
 */
public class HypothesisSpecifier {

  public enum Type {
    DESC,
    NAME,
    VRF
  }

  public static HypothesisSpecifier ALL = new HypothesisSpecifier(".*");

  public static HypothesisSpecifier NONE = new HypothesisSpecifier("");

  private final String _expression;

  private final Pattern _regex;

  private final Type _type;

  @JsonCreator
  public HypothesisSpecifier(String expression) {
    _expression = expression;

    String[] parts = expression.split(":");

    if (parts.length == 1) {
      _type = Type.NAME;
      _regex = Pattern.compile(_expression);
    } else if (parts.length == 2) {
      try {
        _type = Type.valueOf(parts[0].toUpperCase());
        _regex = Pattern.compile(parts[1]);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Illegal HypothesisSpecifier filter "
                + parts[0]
                + ".  Should be one of "
                + Arrays.stream(Type.values())
                    .map(v -> v.toString())
                    .collect(Collectors.joining(", ")));
      }
    } else {
      throw new IllegalArgumentException("Cannot parse HypothesisSpecifier " + expression);
    }
  }

  public HypothesisSpecifier(Pattern regex) {
    _expression = regex.toString();
    _type = Type.NAME;
    _regex = regex;
  }

  @JsonIgnore
  public Pattern getRegex() {
    return _regex;
  }

  @JsonIgnore
  public Type getType() {
    return _type;
  }

  public boolean matches(Interface iface) {
    switch (_type) {
      case DESC:
        return _regex.matcher(iface.getDescription()).matches();
      case NAME:
        return _regex.matcher(iface.getName()).matches();
      case VRF:
        return _regex.matcher(iface.getVrfName()).matches();
      default:
        throw new BatfishException("Unhandled HypothesisSpecifier type: " + _type);
    }
  }

  @Override
  @JsonValue
  public String toString() {
    return _expression;
  }
}